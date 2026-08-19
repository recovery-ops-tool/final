package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.LoginRequest;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.exception.AccountDisabledException;
import com.recoverpro.server.exception.AccountLockedException;
import com.recoverpro.server.exception.InvalidTotpException;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.MfaService;
import com.recoverpro.server.service.PasswordResetService;
import com.recoverpro.server.service.RefreshTokenRotationService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.util.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 08 TASK 8.4.b: "an audit trail that only records successes is useless for
 * investigation." Each of these failure branches wrote nothing to unified_audit_events at all
 * before this pass -- pins that they now do, and with a distinguishing reason.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplAuditCoverageTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RateLimiter rateLimiter;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private UserMapper userMapper;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private MfaService mfaService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private RefreshTokenRotationService refreshTokenRotationService;
    @Mock private HttpServletRequest httpRequest;

    private AuthServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        service = new AuthServiceImpl(userRepository, organizationRepository, refreshTokenRepository,
                passwordEncoder, rateLimiter, redisTemplate, props, userMapper,
                auditLogService, auditService, new ObjectMapper(), emailService, mfaService, passwordResetService,
                refreshTokenRotationService);
        user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .passwordHash("hashed").enabled(true).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true);
        lenient().when(rateLimiter.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
        lenient().when(mfaService.requiresMfaEnrollment(user)).thenReturn(false);
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setEmail("agent@example.com");
        request.setPassword("Password123!");
        return request;
    }

    @Test
    void login_disabledAccount_recordsAuthLoginFailed() {
        user.setEnabled(false);

        assertThatThrownBy(() -> service.login(loginRequest(), httpRequest))
                .isInstanceOf(AccountDisabledException.class);

        verify(auditService).record(argThat(evt ->
                evt.getAction() == AuditAction.AUTH_LOGIN_FAILED
                        && evt.getResourceId().equals(user.getId().toString())));
    }

    @Test
    void login_invalidTotp_recordsAuthLoginFailed() {
        user.setMfaEnabled(true);
        user.setMfaSecret("secret");
        when(mfaService.verifyTotpForLogin(user.getId(), "secret", "000000")).thenReturn(false);

        LoginRequest request = loginRequest();
        request.setTotpCode("000000");

        assertThatThrownBy(() -> service.login(request, httpRequest))
                .isInstanceOf(InvalidTotpException.class);

        verify(auditService).record(argThat(evt -> evt.getAction() == AuditAction.AUTH_LOGIN_FAILED));
    }

    @Test
    void login_invalidRecoveryCode_recordsAuthLoginFailed() {
        user.setMfaEnabled(true);
        user.setMfaSecret("secret");
        when(mfaService.redeemRecoveryCode(user.getId(), "WRONG-CODE")).thenReturn(false);

        LoginRequest request = loginRequest();
        request.setRecoveryCode("WRONG-CODE");

        assertThatThrownBy(() -> service.login(request, httpRequest))
                .isInstanceOf(InvalidTotpException.class);

        verify(auditService).record(argThat(evt -> evt.getAction() == AuditAction.AUTH_LOGIN_FAILED));
    }

    /** The lockout TRANSITION itself (not just "rejected, already locked") gets its own audit
     *  row, regardless of which failure path (here: TOTP) triggered it. */
    @Test
    void login_repeatedMfaFailureCrossesLockoutThreshold_recordsAccountLockedAudit() {
        AppProperties props = new AppProperties();
        props.getSecurity().setMaxLoginAttempts(1); // one strike and it's locked
        service = new AuthServiceImpl(userRepository, organizationRepository, refreshTokenRepository,
                passwordEncoder, rateLimiter, redisTemplate, props, userMapper,
                auditLogService, auditService, new ObjectMapper(), emailService, mfaService, passwordResetService,
                refreshTokenRotationService);
        user.setMfaEnabled(true);
        user.setMfaSecret("secret");
        when(mfaService.verifyTotpForLogin(user.getId(), "secret", "000000")).thenReturn(false);

        LoginRequest request = loginRequest();
        request.setTotpCode("000000");

        assertThatThrownBy(() -> service.login(request, httpRequest))
                .isInstanceOf(AccountLockedException.class);

        verify(auditService).record(argThat(evt ->
                evt.getAction() == AuditAction.AUTH_LOGIN_FAILED
                        && "Account locked after repeated failed attempts".equals(evt.getReason())));
    }
}
