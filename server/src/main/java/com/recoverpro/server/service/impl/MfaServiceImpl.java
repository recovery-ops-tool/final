package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.EnableMfaRequest;
import com.recoverpro.server.dto.response.MfaEnableResponse;
import com.recoverpro.server.dto.response.MfaSetupResponse;
import com.recoverpro.server.entity.MfaRecoveryCode;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.exception.InvalidTotpException;
import com.recoverpro.server.repository.MfaRecoveryCodeRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.totp.TotpService;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.MfaService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.util.RateLimiter;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MfaServiceImpl implements MfaService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final MfaRecoveryCodeRepository mfaRecoveryCodeRepository;
    private final TotpService totpService;
    private final StringRedisTemplate redisTemplate;
    private final RateLimiter rateLimiter;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.security.mfa.enforce:false}")
    private boolean mfaEnforce;

    @Value("${app.security.mfa.required-roles:ROLE_PLATFORM_ADMIN,ROLE_ORG_ADMIN}")
    private String mfaRequiredRolesCsv;

    private static final String MFA_SESSION_PREFIX = "mfa:session:";
    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";
    private static final String RECOVERY_CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int RECOVERY_CODE_COUNT = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional(readOnly = true)
    public MfaSetupResponse initMfaSetup(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        GoogleAuthenticatorKey key = totpService.generateSecret();
        String secret = key.getKey();
        redisTemplate.opsForValue().set("mfa:pending:" + userId, secret, 10, TimeUnit.MINUTES);
        auditLogService.logUserAction(userId, "MFA_SETUP_INITIATED", "MFA setup started");

        return MfaSetupResponse.builder()
                .secret(secret)
                .qrCodeUri(totpService.getQrCodeUri(user.getEmail(), secret))
                .manualEntryKey(formatManualKey(secret))
                .build();
    }

    @Override
    @Transactional
    public MfaEnableResponse enableMfa(UUID userId, EnableMfaRequest request) {
        String enableRateKey = "mfa:enable:" + userId;
        if (!rateLimiter.isAllowed(enableRateKey, 10, 10)) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(enableRateKey);
            throw new RateLimitExceededException("Too many attempts", retryAfter);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String pendingSecret = redisTemplate.opsForValue().get("mfa:pending:" + userId);
        if (pendingSecret == null) {
            throw new InvalidTotpException("MFA setup session expired. Please restart setup.");
        }

        String usedCodeKey = "mfa:used:" + userId + ":" + request.getTotpCode();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(usedCodeKey))) {
            throw new InvalidTotpException("Invalid TOTP code - please check your authenticator app");
        }
        if (!totpService.verifyCode(pendingSecret, request.getTotpCode())) {
            throw new InvalidTotpException("Invalid TOTP code - please check your authenticator app");
        }

        redisTemplate.opsForValue().set(usedCodeKey, "1", 60, TimeUnit.SECONDS);

        user.setMfaSecret(pendingSecret);
        user.setMfaEnabled(true);
        userRepository.save(user);
        redisTemplate.delete("mfa:pending:" + userId);

        List<String> plainCodes = generateAndPersistRecoveryCodes(userId);
        evictUserProfileCache(userId);
        auditLogService.logUserAction(userId, "MFA_ENABLED", "MFA successfully enabled");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_MFA_ENABLED)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .build());

        return MfaEnableResponse.builder().recoveryCodes(plainCodes).build();
    }

    @Override
    @Transactional
    public void disableMfa(UUID userId, String totpCode) {
        String disableRateKey = "mfa:disable:" + userId;
        if (!rateLimiter.isAllowed(disableRateKey, 5, 10)) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(disableRateKey);
            throw new RateLimitExceededException("Too many attempts", retryAfter);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isMfaEnabled()) {
            throw new InvalidTotpException("MFA is not enabled for this account");
        }

        String usedCodeKey = "mfa:used:" + userId + ":" + totpCode;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(usedCodeKey))) {
            throw new InvalidTotpException("Invalid TOTP code");
        }
        if (!totpService.verifyCode(user.getMfaSecret(), totpCode)) {
            throw new InvalidTotpException("Invalid TOTP code");
        }

        redisTemplate.opsForValue().set(usedCodeKey, "1", 60, TimeUnit.SECONDS);
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        userRepository.save(user);
        mfaRecoveryCodeRepository.deleteAllByUserId(userId);
        evictUserProfileCache(userId);
        auditLogService.logUserAction(userId, "MFA_DISABLED", "MFA successfully disabled");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_MFA_DISABLED)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .build());
    }

    /**
     * SYSTEM 08 TASK 8.3.c: the org-level check is unconditional (an org that has opted itself
     * into MFA is not gated by the platform-wide app.security.mfa.enforce switch -- that switch
     * governs the SEPARATE role-based policy below, a platform decision, not a tenant one).
     * Written test-first per the task's own instruction (MfaServiceImplTest, before this method
     * body existed) -- getting this wrong creates an authentication bypass.
     */
    @Override
    public boolean requiresMfaEnrollment(User user) {
        if (organizationRequiresMfa(user.getOrganizationId())) {
            return true;
        }
        if (!mfaEnforce) return false;
        if (mfaRequiredRolesCsv == null || mfaRequiredRolesCsv.isBlank()) return false;
        List<String> required = Arrays.stream(mfaRequiredRolesCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return user.getRoles().stream().anyMatch(r -> required.contains(r.getName()));
    }

    private boolean organizationRequiresMfa(UUID organizationId) {
        if (organizationId == null) return false; // platform admins belong to no tenant org
        return organizationRepository.findById(organizationId)
                .map(Organization::isMfaRequired)
                .orElse(false);
    }

    @Override
    public String storeMfaSession(UUID userId) {
        String token = generateSecureToken();
        redisTemplate.opsForValue().set(MFA_SESSION_PREFIX + token, userId.toString(), 5, TimeUnit.MINUTES);
        return token;
    }

    @Override
    public boolean verifyTotpForLogin(UUID userId, String mfaSecret, String totpCode) {
        String usedCodeKey = "mfa:used:" + userId + ":" + totpCode;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(usedCodeKey))) {
            return false;
        }
        if (!totpService.verifyCode(mfaSecret, totpCode)) {
            return false;
        }
        redisTemplate.opsForValue().set(usedCodeKey, "1", 60, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean redeemRecoveryCode(UUID userId, String rawCode) {
        String normalized = rawCode.trim().toUpperCase().replace("-", "");
        for (MfaRecoveryCode code : mfaRecoveryCodeRepository.findByUserIdAndUsedFalse(userId)) {
            if (passwordEncoder.matches(normalized, code.getCodeHash())) {
                code.setUsed(true);
                code.setUsedAt(Instant.now());
                mfaRecoveryCodeRepository.save(code);
                return true;
            }
        }
        return false;
    }

    private void evictUserProfileCache(UUID userId) {
        redisTemplate.delete(USER_PROFILE_CACHE_PREFIX + userId);
    }

    private List<String> generateAndPersistRecoveryCodes(UUID userId) {
        mfaRecoveryCodeRepository.deleteAllByUserId(userId);
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<MfaRecoveryCode> entities = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = randomSegment(4) + "-" + randomSegment(4) + "-" + randomSegment(4);
            plain.add(code);
            entities.add(MfaRecoveryCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code.replace("-", "")))
                    .build());
        }
        mfaRecoveryCodeRepository.saveAll(entities);
        return plain;
    }

    private String randomSegment(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RECOVERY_CODE_ALPHABET.charAt(SECURE_RANDOM.nextInt(RECOVERY_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String formatManualKey(String secret) {
        return secret.replaceAll("(.{4})", "$1 ").trim();
    }
}
