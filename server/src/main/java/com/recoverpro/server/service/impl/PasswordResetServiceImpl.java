package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.ForgotPasswordRequest;
import com.recoverpro.server.dto.request.ResetPasswordRequest;
import com.recoverpro.server.dto.request.VerifyOtpRequest;
import com.recoverpro.server.entity.PasswordResetToken;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.exception.InvalidOtpException;
import com.recoverpro.server.repository.PasswordResetTokenRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.PasswordResetService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.util.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;

    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        AppProperties.Security sec = appProperties.getSecurity();
        String rateLimitKey = "forgotpw:" + email;

        if (!rateLimiter.isAllowed(rateLimitKey, sec.getForgotPasswordMaxAttempts(), sec.getForgotPasswordWindowMinutes())) {
            return; // silent — do not reveal rate-limit
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return; // silent — do not reveal whether this email has an account
        }

        passwordResetTokenRepository.invalidateAllByUserId(user.getId());

        String otp = generateOtp();
        int expiryMinutes = sec.getOtpExpiryMinutes();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES))
                .build();
        passwordResetTokenRepository.save(token);
        emailService.sendPasswordResetOtp(user.getEmail(), otp, expiryMinutes);
        auditLogService.logUserAction(user.getId(), "PASSWORD_RESET_REQUESTED", "OTP sent to email");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_PASSWORD_RESET_REQUESTED)
                .resourceType(AuditResourceType.USER)
                .resourceId(user.getId().toString())
                .build());
    }

    @Override
    @Transactional
    public void verifyResetOtp(VerifyOtpRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        AppProperties.Security sec = appProperties.getSecurity();
        String rateLimitKey = "resetpw:" + email;

        if (!rateLimiter.isAllowed(rateLimitKey, sec.getOtpMaxAttempts(), sec.getOtpWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(rateLimitKey);
            throw new RateLimitExceededException("Too many attempts. Retry after " + retryAfter + "s", retryAfter);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidOtpException("Invalid or expired OTP"));

        PasswordResetToken token = passwordResetTokenRepository
                .findValidByUserId(user.getId(), Instant.now())
                .orElseThrow(() -> new InvalidOtpException("Invalid or expired OTP"));

        if (!passwordEncoder.matches(request.getOtp(), token.getOtpHash())) {
            auditLogService.logUserAction(user.getId(), "PASSWORD_RESET_OTP_FAILED", "Invalid OTP");
            // SYSTEM 08 TASK 8.4.b: was missing the structured unified_audit_events row entirely
            // (only the legacy free-text log got one) -- inconsistent with resetPassword()'s own
            // wrong-OTP branch just below, which already records both. Same action+FAILURE result
            // as that one: both represent an OTP-verification attempt failing within this reset
            // flow, and keeping one action for both keeps "did someone try wrong OTPs against this
            // account" a single, simple query.
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.AUTH_PASSWORD_RESET_COMPLETED)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(user.getId().toString())
                    .result(AuditResult.FAILURE)
                    .reason("Invalid OTP provided")
                    .build());
            throw new InvalidOtpException("Invalid or expired OTP");
        }
        rateLimiter.reset(rateLimitKey);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        AppProperties.Security sec = appProperties.getSecurity();
        String rateLimitKey = "resetpw:" + email;

        if (!rateLimiter.isAllowed(rateLimitKey, sec.getOtpMaxAttempts(), sec.getOtpWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(rateLimitKey);
            throw new RateLimitExceededException("Too many attempts. Retry after " + retryAfter + "s", retryAfter);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidOtpException("Invalid or expired OTP"));

        PasswordResetToken token = passwordResetTokenRepository
                .findValidByUserId(user.getId(), Instant.now())
                .orElseThrow(() -> new InvalidOtpException("Invalid or expired OTP"));

        if (!passwordEncoder.matches(request.getOtp(), token.getOtpHash())) {
            auditLogService.logUserAction(user.getId(), "PASSWORD_RESET_FAILED", "Invalid OTP provided");
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.AUTH_PASSWORD_RESET_COMPLETED)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(user.getId().toString())
                    .result(AuditResult.FAILURE)
                    .reason("Invalid OTP provided")
                    .build());
            throw new InvalidOtpException("Invalid or expired OTP");
        }

        rateLimiter.reset(rateLimitKey);
        token.setUsed(true);
        passwordResetTokenRepository.save(token);

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
        evictUserProfileCache(user.getId());
        auditLogService.logUserAction(user.getId(), "PASSWORD_RESET_SUCCESS", "Password changed via reset flow");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_PASSWORD_RESET_COMPLETED)
                .resourceType(AuditResourceType.USER)
                .resourceId(user.getId().toString())
                .build());
    }

    private void evictUserProfileCache(java.util.UUID userId) {
        redisTemplate.delete(USER_PROFILE_CACHE_PREFIX + userId);
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
