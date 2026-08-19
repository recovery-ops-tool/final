package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.LoginRequest;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.exception.MfaSetupRequiredException;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 08 TASK 8.3.c: "getting this wrong creates a bypass" -- the literal acceptance is that a
 * user who must enroll but hasn't gets NO usable access token, only the ability to complete
 * enrollment. This pins that AuthServiceImpl.login() actually enforces what
 * MfaService#requiresMfaEnrollment decides, end to end through the real login() method (not just
 * that the decision function itself returns the right boolean, which
 * MfaServiceImplTest already covers separately).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplMfaEnforcementTest {

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
        service = new AuthServiceImpl(userRepository, organizationRepository, refreshTokenRepository,
                passwordEncoder, rateLimiter, redisTemplate, new AppProperties(), userMapper,
                auditLogService, auditService, new ObjectMapper(), emailService, mfaService, passwordResetService,
                refreshTokenRotationService);
        user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .passwordHash("hashed").enabled(true).mfaEnabled(false).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true);
        lenient().when(rateLimiter.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setEmail("agent@example.com");
        request.setPassword("Password123!");
        return request;
    }

    @Test
    void login_enrollmentRequiredAndNotEnrolled_throwsAndIssuesNoToken() {
        when(mfaService.requiresMfaEnrollment(user)).thenReturn(true);

        assertThatThrownBy(() -> service.login(loginRequest(), httpRequest))
                .isInstanceOf(MfaSetupRequiredException.class);

        verify(refreshTokenRotationService, never()).buildFullAuthResponse(any(), any());
    }

    @Test
    void login_enrollmentRequiredButAlreadyEnrolled_proceedsToMfaChallengeNotBlocked() {
        user.setMfaEnabled(true);
        user.setMfaSecret("secret");
        when(mfaService.requiresMfaEnrollment(user)).thenReturn(true);
        when(mfaService.storeMfaSession(user.getId())).thenReturn("mfa-session-token");

        var response = service.login(loginRequest(), httpRequest);

        // Already enrolled -- must reach the normal "enter your TOTP code" step, not the
        // enrollment-required block, and still no access token without the code.
        org.assertj.core.api.Assertions.assertThat(response.isMfaRequired()).isTrue();
        org.assertj.core.api.Assertions.assertThat(response.getMfaSessionToken()).isEqualTo("mfa-session-token");
        verify(refreshTokenRotationService, never()).buildFullAuthResponse(any(), any());
    }

    @Test
    void login_enrollmentNotRequired_proceedsNormally() {
        when(mfaService.requiresMfaEnrollment(user)).thenReturn(false);

        service.login(loginRequest(), httpRequest);

        verify(refreshTokenRotationService).buildFullAuthResponse(any(), any());
    }
}
