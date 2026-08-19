package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.dto.response.MfaEnableResponse;
import com.recoverpro.server.dto.response.MfaSetupResponse;
import com.recoverpro.server.dto.response.AuthSessionResponse;
import com.recoverpro.server.dto.response.UserResponse;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.exception.*;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.AuthService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.MfaService;
import com.recoverpro.server.service.PasswordResetService;
import com.recoverpro.server.service.RefreshTokenRotationService;
import com.recoverpro.server.util.ClientIpResolver;
import com.recoverpro.server.util.RateLimiter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Core identity flows (registration, login, current-user, change-password). MFA, password-reset,
 * and refresh-token/session concerns live in {@link MfaService}, {@link PasswordResetService},
 * and {@link RefreshTokenRotationService} respectively (SYSTEM-PLAN SP39 -- this class was ~640
 * lines mixing all four).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final UserMapper userMapper;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;
    private final MfaService mfaService;
    private final PasswordResetService passwordResetService;
    private final RefreshTokenRotationService refreshTokenRotationService;

    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";

    private String dummyPasswordHash;

    @PostConstruct
    private void init() {
        dummyPasswordHash = passwordEncoder.encode("__DUMMY_CONSTANT_TIME__");
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        String cacheKey = USER_PROFILE_CACHE_PREFIX + userId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, UserResponse.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached profile for user {}: {}", userId, e.getMessage());
                redisTemplate.delete(cacheKey);
            }
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        UserResponse response = userMapper.toResponse(user);
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(response), 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to cache user profile for {}: {}", userId, e.getMessage());
        }
        return response;
    }

    private void evictUserProfileCache(UUID userId) {
        redisTemplate.delete(USER_PROFILE_CACHE_PREFIX + userId);
    }

    private boolean isOrganizationActive(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .map(org -> org.isActive() && org.getDeletedAt() == null)
                .orElse(true);
    }

    @Override
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String ip = ClientIpResolver.resolve(httpRequest);
        String email = request.getEmail().toLowerCase().trim();
        String emailRateLimitKey = "email:" + email;
        AppProperties.Security sec = appProperties.getSecurity();

        if (!rateLimiter.isAllowed(ip, sec.getMaxLoginAttempts(), sec.getLoginWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(ip);
            throw new RateLimitExceededException("Too many login attempts. Retry after " + retryAfter + "s", retryAfter);
        }
        if (!rateLimiter.isAllowed(emailRateLimitKey, sec.getMaxLoginAttempts(), sec.getLoginWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(emailRateLimitKey);
            throw new RateLimitExceededException("Too many login attempts. Retry after " + retryAfter + "s", retryAfter);
        }

        User user = userRepository.findByEmail(email).orElse(null);
        String hashToCheck = (user != null) ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordValid = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (user == null || !passwordValid) {
            if (user != null) {
                handleFailedAttempt(user);
                auditLogService.logUserAction(user.getId(), "LOGIN_FAILED", "Invalid password from IP: " + ip);
                auditLoginFailure(user.getId(), "Invalid password");
            }
            throw new InvalidCredentialsException("Invalid email or password");
        }

        if (!user.isEnabled()) {
            // SYSTEM 08 TASK 8.4.b: was thrown with zero audit trail at all -- a disabled-account
            // login attempt (e.g. an offboarded employee's credentials still being used) is exactly
            // the kind of event an investigation needs to find, same as every other rejection
            // branch in this method already produces one.
            auditLogService.logUserAction(user.getId(), "LOGIN_FAILED", "Account disabled, IP: " + ip);
            auditLoginFailure(user.getId(), "Account disabled");
            throw new AccountDisabledException("Account is disabled");
        }
        if (user.getOrganizationId() != null && !isOrganizationActive(user.getOrganizationId())) {
            auditLogService.logUserAction(user.getId(), "LOGIN_BLOCKED", "Organization suspended, IP: " + ip);
            auditLoginFailure(user.getId(), "Organization suspended");
            throw new OrganizationSuspendedException(
                    "Your organization's access has been suspended. Contact your administrator.");
        }
        if (user.isCurrentlyLocked()) {
            long retryAfter = computeLockoutRetryAfter(user);
            auditLogService.logUserAction(user.getId(), "LOGIN_FAILED", "Account locked from IP: " + ip);
            auditLoginFailure(user.getId(), "Account locked");
            throw new AccountLockedException("Account locked. Try again in " + retryAfter + "s", retryAfter);
        }

        if (mfaService.requiresMfaEnrollment(user) && !user.isMfaEnabled()) {
            auditLogService.logUserAction(user.getId(), "LOGIN_BLOCKED", "MFA enrollment required");
            auditLoginFailure(user.getId(), "MFA enrollment required");
            throw new MfaSetupRequiredException("MFA enrollment is required for this account.");
        }

        if (user.isMfaEnabled()) {
            boolean hasTotp = request.getTotpCode() != null && !request.getTotpCode().isBlank();
            boolean hasRecoveryCode = request.getRecoveryCode() != null && !request.getRecoveryCode().isBlank();

            if (!hasTotp && !hasRecoveryCode) {
                String sessionToken = mfaService.storeMfaSession(user.getId());
                return AuthResponse.builder().mfaRequired(true).mfaSessionToken(sessionToken).build();
            }

            if (hasRecoveryCode) {
                if (!mfaService.redeemRecoveryCode(user.getId(), request.getRecoveryCode())) {
                    // SYSTEM 08 TASK 8.4.b: was unaudited -- an MFA failure at login is a
                    // security-relevant event on its own, distinct from the password check.
                    // Audited BEFORE handleFailedAttempt(), which can itself throw
                    // AccountLockedException (crossing the lockout threshold) -- auditing after
                    // would silently skip this row on exactly that path.
                    auditLogService.logUserAction(user.getId(), "LOGIN_FAILED",
                            "Invalid recovery code from IP: " + ip);
                    auditLoginFailure(user.getId(), "Invalid recovery code");
                    handleFailedAttempt(user);
                    throw new InvalidTotpException("Invalid or already-used recovery code");
                }
                log.info("MFA recovery code redeemed for user {}", user.getId());
            } else {
                if (!mfaService.verifyTotpForLogin(user.getId(), user.getMfaSecret(), request.getTotpCode())) {
                    auditLogService.logUserAction(user.getId(), "LOGIN_FAILED",
                            "Invalid TOTP code from IP: " + ip);
                    auditLoginFailure(user.getId(), "Invalid TOTP code");
                    handleFailedAttempt(user);
                    throw new InvalidTotpException("Invalid TOTP code");
                }
            }
        }

        rateLimiter.reset(ip);
        rateLimiter.reset(emailRateLimitKey);
        userRepository.recordSuccessfulLogin(user.getId(), Instant.now());
        auditLogService.logUserAction(user.getId(), "LOGIN", "Successful login from IP: " + ip);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_LOGIN_SUCCESS)
                .resourceType(AuditResourceType.USER)
                .resourceId(user.getId().toString())
                .build());

        return refreshTokenRotationService.buildFullAuthResponse(user, httpRequest);
    }

    private void auditLoginFailure(UUID userId, String reason) {
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_LOGIN_FAILED)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .result(AuditResult.DENIED)
                .reason(reason)
                .build());
    }

    @Override
    public AuthResponse refreshToken(RefreshTokenRequest request, HttpServletRequest httpRequest) {
        return refreshTokenRotationService.rotate(request, httpRequest);
    }

    @Override
    public void logout(String accessToken, UUID userId, String refreshToken) {
        refreshTokenRotationService.logout(accessToken, userId, refreshToken);
    }

    @Override
    public void logoutAllDevices(UUID userId, String accessToken) {
        refreshTokenRotationService.logoutAllDevices(userId, accessToken);
    }

    @Override
    public List<AuthSessionResponse> listSessions(UUID userId, String currentDeviceId) {
        return refreshTokenRotationService.listSessions(userId, currentDeviceId);
    }

    @Override
    public void revokeSession(UUID userId, UUID sessionId) {
        refreshTokenRotationService.revokeSession(userId, sessionId);
    }

    @Override
    public int revokeOtherSessions(UUID userId, String currentDeviceId) {
        return refreshTokenRotationService.revokeOtherSessions(userId, currentDeviceId);
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        passwordResetService.forgotPassword(request);
    }

    @Override
    public void verifyResetOtp(VerifyOtpRequest request) {
        passwordResetService.verifyResetOtp(request);
    }

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
    }

    @Override
    public void changePassword(UUID userId, ChangePasswordRequest request, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            auditLogService.logUserAction(userId, "PASSWORD_CHANGE_FAILED", "Incorrect current password");
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.AUTH_PASSWORD_CHANGED)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(userId.toString())
                    .result(AuditResult.FAILURE)
                    .reason("Incorrect current password")
                    .build());
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);

        refreshTokenRotationService.blacklistToken(accessToken);
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        evictUserProfileCache(userId);
        auditLogService.logUserAction(userId, "PASSWORD_CHANGE_SUCCESS", "Password changed successfully");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_PASSWORD_CHANGED)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public MfaSetupResponse initMfaSetup(UUID userId) {
        return mfaService.initMfaSetup(userId);
    }

    @Override
    public MfaEnableResponse enableMfa(UUID userId, EnableMfaRequest request) {
        return mfaService.enableMfa(userId, request);
    }

    @Override
    public void disableMfa(UUID userId, String totpCode) {
        mfaService.disableMfa(userId, totpCode);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private long computeLockoutRetryAfter(User user) {
        if (user.getLockoutUntil() == null) return 0L;
        return Math.max(0L,
                java.time.Duration.between(Instant.now(), user.getLockoutUntil()).toSeconds());
    }

    private void handleFailedAttempt(User user) {
        AppProperties.Security sec = appProperties.getSecurity();
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= sec.getMaxLoginAttempts()) {
            int lockoutNumber = user.getLockoutCount() + 1;
            user.setLockoutCount(lockoutNumber);
            long backoffMinutes = Math.min(
                    (long) sec.getLockoutDurationMinutes() * (1L << Math.min(lockoutNumber - 1, 5)),
                    1440L);
            user.setAccountLocked(true);
            user.setLockoutUntil(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES));
            user.setFailedLoginAttempts(0);
            userRepository.save(user);
            emailService.sendAccountLockedAlert(user.getEmail());
            // SYSTEM 08 TASK 8.4.b: distinct from the "rejected because ALREADY locked" audit row
            // (login()'s isCurrentlyLocked() branch) -- this is the transition itself, the moment
            // lockout engages, regardless of which of the three call sites (password, TOTP,
            // recovery code) triggered it.
            auditLogService.logUserAction(user.getId(), "ACCOUNT_LOCKED",
                    "Account locked for " + backoffMinutes + " minutes after repeated failures");
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.AUTH_LOGIN_FAILED)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(user.getId().toString())
                    .result(AuditResult.DENIED)
                    .reason("Account locked after repeated failed attempts")
                    .build());
            throw new AccountLockedException("Account locked for " + backoffMinutes + " minutes",
                    backoffMinutes * 60);
        }
        userRepository.save(user);
    }
}
