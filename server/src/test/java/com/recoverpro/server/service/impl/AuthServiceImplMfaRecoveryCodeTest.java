package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.LoginRequest;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.exception.InvalidTotpException;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.MfaService;
import com.recoverpro.server.service.PasswordResetService;
import com.recoverpro.server.service.RefreshTokenRotationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN SP35: recovery codes were generated on enableMfa but no login-time redemption path
 * existed anywhere. Since SP39, the actual redemption matching lives in MfaServiceImpl (see
 * MfaServiceImplTest); this test only verifies AuthServiceImpl.login wires a recovery-code login
 * attempt to MfaService correctly.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplMfaRecoveryCodeTest {

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

        user = User.builder()
                .id(UUID.randomUUID())
                .email("agent@example.com")
                .passwordHash("hashed")
                .enabled(true)
                .mfaEnabled(true)
                .mfaSecret("secret")
                .build();
    }

    private void stubCommonLoginPath() {
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true);
        when(rateLimiter.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void login_validRecoveryCode_delegatesToMfaServiceAndSucceeds() {
        stubCommonLoginPath();
        when(mfaService.redeemRecoveryCode(user.getId(), "ABCD-1234-WXYZ")).thenReturn(true);
        AuthResponse expected = AuthResponse.builder().accessToken("access-token").build();
        when(refreshTokenRotationService.buildFullAuthResponse(eq(user), any())).thenReturn(expected);

        LoginRequest request = new LoginRequest();
        request.setEmail("agent@example.com");
        request.setPassword("Password123!");
        request.setRecoveryCode("ABCD-1234-WXYZ");

        AuthResponse response = service.login(request, httpRequest);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        verify(mfaService).redeemRecoveryCode(user.getId(), "ABCD-1234-WXYZ");
    }

    @Test
    void login_invalidRecoveryCode_throwsInvalidTotpExceptionAndDoesNotIssueTokens() {
        stubCommonLoginPath();
        when(mfaService.redeemRecoveryCode(user.getId(), "WRONG-CODE-0000")).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setEmail("agent@example.com");
        request.setPassword("Password123!");
        request.setRecoveryCode("WRONG-CODE-0000");

        assertThatThrownBy(() -> service.login(request, httpRequest))
                .isInstanceOf(InvalidTotpException.class);

        verify(refreshTokenRotationService, org.mockito.Mockito.never()).buildFullAuthResponse(any(), any());
    }
}
