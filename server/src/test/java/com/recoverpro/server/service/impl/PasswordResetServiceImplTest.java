package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.ResetPasswordRequest;
import com.recoverpro.server.dto.request.VerifyOtpRequest;
import com.recoverpro.server.entity.PasswordResetToken;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.exception.InvalidOtpException;
import com.recoverpro.server.repository.PasswordResetTokenRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.util.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
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
 * SYSTEM-PLAN SP39: forgot/verify/reset-password flow extracted from AuthServiceImpl into its
 * own PasswordResetService.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private RateLimiter rateLimiter;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private StringRedisTemplate redisTemplate;

    private PasswordResetServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetServiceImpl(userRepository, passwordResetTokenRepository,
                refreshTokenRepository, passwordEncoder, emailService, rateLimiter, new AppProperties(),
                auditLogService, auditService, redisTemplate);
        user = User.builder().id(UUID.randomUUID()).email("agent@example.com").build();
        lenient().when(rateLimiter.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void resetPassword_validOtp_updatesPasswordAndRevokesSessions() {
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        PasswordResetToken token = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user).otpHash("otp-hash").used(false)
                .expiresAt(Instant.now().plusSeconds(600)).build();
        when(passwordResetTokenRepository.findValidByUserId(any(), any())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("123456", "otp-hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPassword1!")).thenReturn("new-hash");

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEmail("agent@example.com");
        request.setOtp("123456");
        request.setNewPassword("NewPassword1!");

        service.resetPassword(request);

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(token.isUsed()).isTrue();
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any());
        verify(auditLogService).logUserAction(eq(user.getId()), eq("PASSWORD_RESET_SUCCESS"), any());
    }

    @Test
    void resetPassword_invalidOtp_throwsAndDoesNotChangePassword() {
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        PasswordResetToken token = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user).otpHash("otp-hash").used(false)
                .expiresAt(Instant.now().plusSeconds(600)).build();
        when(passwordResetTokenRepository.findValidByUserId(any(), any())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("000000", "otp-hash")).thenReturn(false);

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEmail("agent@example.com");
        request.setOtp("000000");
        request.setNewPassword("NewPassword1!");

        assertThatThrownBy(() -> service.resetPassword(request)).isInstanceOf(InvalidOtpException.class);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void verifyResetOtp_valid_resetsRateLimitCounter() {
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        PasswordResetToken token = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user).otpHash("otp-hash").used(false)
                .expiresAt(Instant.now().plusSeconds(600)).build();
        when(passwordResetTokenRepository.findValidByUserId(any(), any())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("123456", "otp-hash")).thenReturn(true);

        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("agent@example.com");
        request.setOtp("123456");

        service.verifyResetOtp(request);

        verify(rateLimiter).reset("resetpw:agent@example.com");
    }

    /** SYSTEM 08 TASK 8.4.b: was only ever written to the legacy free-text log, never to
     *  unified_audit_events -- inconsistent with resetPassword()'s own wrong-OTP branch just
     *  below it in the source, which already records both. */
    @Test
    void verifyResetOtp_invalidOtp_recordsStructuredAuditFailure() {
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        PasswordResetToken token = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user).otpHash("otp-hash").used(false)
                .expiresAt(Instant.now().plusSeconds(600)).build();
        when(passwordResetTokenRepository.findValidByUserId(any(), any())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("000000", "otp-hash")).thenReturn(false);

        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("agent@example.com");
        request.setOtp("000000");

        assertThatThrownBy(() -> service.verifyResetOtp(request)).isInstanceOf(InvalidOtpException.class);

        verify(auditService).record(org.mockito.ArgumentMatchers.argThat(evt ->
                evt.getAction() == com.recoverpro.server.enums.AuditAction.AUTH_PASSWORD_RESET_COMPLETED
                        && evt.getResult() == com.recoverpro.server.enums.AuditResult.FAILURE));
    }
}
