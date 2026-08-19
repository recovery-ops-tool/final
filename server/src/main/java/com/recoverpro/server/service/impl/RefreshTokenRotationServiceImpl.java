package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.RefreshTokenRequest;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.dto.response.AuthSessionResponse;
import com.recoverpro.server.entity.RefreshToken;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.exception.AccountDisabledException;
import com.recoverpro.server.exception.AccountLockedException;
import com.recoverpro.server.exception.InvalidTokenException;
import com.recoverpro.server.exception.OrganizationSuspendedException;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.observability.BusinessMetrics;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.security.jwt.JwtTokenProvider;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.OpsAlertService;
import com.recoverpro.server.service.RefreshTokenRotationService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.security.SessionAnomalyDetector;
import com.recoverpro.server.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenRotationServiceImpl implements RefreshTokenRotationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final OrganizationRepository organizationRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final SessionAnomalyDetector sessionAnomalyDetector;
    private final AppProperties appProperties;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final NotificationService notificationService;
    private final OpsAlertService opsAlertService;

    private static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional
    public AuthResponse buildFullAuthResponse(User user, HttpServletRequest request) {
        UserPrincipal principal = new UserPrincipal(user);
        String accessToken = jwtTokenProvider.generateAccessToken(principal, user.getId());
        String rawRefreshToken = generateSecureToken();
        String prefix = rawRefreshToken.substring(0, Math.min(16, rawRefreshToken.length()));

        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        String clientIp  = request != null ? ClientIpResolver.resolve(request) : null;
        String deviceId  = request != null ? request.getHeader("X-Device-Id") : null;

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(passwordEncoder.encode(rawRefreshToken))
                .tokenPrefix(prefix)
                .deviceInfo(userAgent)
                .ipAddress(clientIp)
                .expiresAt(Instant.now().plus(
                        appProperties.getJwt().getRefreshTokenExpiryMs() / 1000, ChronoUnit.SECONDS))
                .build();

        sessionAnomalyDetector.inspect(refreshToken, user.getId(), clientIp, userAgent, deviceId);
        refreshTokenRepository.save(refreshToken);

        if (refreshToken.isAnomalyFlagged()) {
            String title = "Suspicious login detected";
            String body = "Login for " + user.getEmail() + " flagged: " + refreshToken.getAnomalyReason()
                    + (clientIp != null ? " (IP " + clientIp + ")" : "") + ".";
            // organization_id IS NULL here, so app_notifications' RLS WITH CHECK allows it
            // unconditionally (see V082) regardless of this connection's current_org_id().
            notificationService.createForPlatformRole(PlatformConstants.ROLE_PLATFORM_ADMIN,
                    NotificationType.PLATFORM_SUSPICIOUS_LOGIN, title, body);

            // This one inserts organization_id = user.getOrganizationId() (non-null for a
            // regular user), which RLS checks against current_org_id() -- but this whole
            // @Transactional method joins login()'s already-open transaction, whose connection
            // was checked out (and its current_org_id GUC stamped, from RlsOrgIdHolder at that
            // earlier moment -- unset, since no JWT exists yet for login/refresh) back at
            // AuthServiceImpl.login()'s very first query. Setting RlsOrgIdHolder now doesn't
            // change an already-checked-out connection's GUC, so route this one insert through
            // createIndependent (REQUIRES_NEW): a fresh connection checkout picks up the value
            // we set right here, correctly scoped to this user's own org.
            RlsOrgIdHolder.set(user.getOrganizationId());
            try {
                notificationService.createIndependent(user.getId(), user.getOrganizationId(),
                        NotificationType.PLATFORM_SUSPICIOUS_LOGIN, title, body);
            } finally {
                RlsOrgIdHolder.clear();
            }
        }

        long expiresIn = appProperties.getJwt().getAccessTokenExpiryMs() / 1000;
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .expiresIn(expiresIn)
                .user(userMapper.toResponse(user))
                .build();
    }

    @Override
    @Transactional
    public AuthResponse rotate(RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String rawToken = request.getRefreshToken();
        String tokenPrefix = rawToken.length() >= 16 ? rawToken.substring(0, 16) : rawToken;

        RefreshToken stored = refreshTokenRepository
                .findByTokenPrefixAndRevokedFalse(tokenPrefix)
                .stream()
                .filter(rt -> passwordEncoder.matches(rawToken, rt.getTokenHash()) && rt.isValid())
                .findFirst()
                .orElse(null);

        if (stored == null) {
            boolean wasTheft = refreshTokenRepository.findByTokenPrefix(tokenPrefix)
                    .stream()
                    .filter(rt -> rt.isRevoked() && passwordEncoder.matches(rawToken, rt.getTokenHash()))
                    .findFirst()
                    .map(rt -> {
                        UUID userId = rt.getUser().getId();
                        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
                        evictUserProfileCache(userId);
                        auditLogService.logUserAction(userId, "TOKEN_THEFT_DETECTED",
                                "Revoked refresh token replayed -- all sessions invalidated");
                        auditService.record(AuditEventRequest.builder()
                                .action(AuditAction.AUTH_TOKEN_THEFT_DETECTED)
                                .resourceType(AuditResourceType.USER)
                                .resourceId(userId.toString())
                                .result(AuditResult.DENIED)
                                .reason("Revoked refresh token replayed -- all sessions invalidated")
                                .build());
                        metrics.recordTokenTheftDetected();
                        log.error("SECURITY: Refresh token reuse detected for user {}. All sessions revoked.", userId);
                        return true;
                    })
                    .orElse(false);
            // SYSTEM 08 TASK 8.4.b: the theft-replay sub-case above was already audited; a
            // genuinely unknown/garbage/expired token (no user to attribute it to at all) was not
            // -- same "no valid credentials" category JwtAuthenticationFilter's AUTH_UNAUTHORIZED
            // already covers for the access-token side (SYSTEM 09 TASK 9.4.b), applied here for
            // the refresh-token endpoint.
            if (!wasTheft) {
                auditService.record(AuditEventRequest.builder()
                        .action(AuditAction.AUTH_UNAUTHORIZED)
                        .resourceType(AuditResourceType.USER)
                        .result(AuditResult.DENIED)
                        .actorTypeOverride(AuditActorType.ANONYMOUS)
                        .reason("Refresh token not found or expired")
                        .build());
            }
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        User user = stored.getUser();
        if (!user.isEnabled()) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
            auditFailedRefresh(user.getId(), "Account disabled");
            throw new AccountDisabledException("Account is disabled");
        }
        if (user.getOrganizationId() != null && !isOrganizationActive(user.getOrganizationId())) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
            auditFailedRefresh(user.getId(), "Organization suspended");
            throw new OrganizationSuspendedException(
                    "Your organization's access has been suspended. Contact your administrator.");
        }
        if (user.isCurrentlyLocked()) {
            long retryAfter = computeLockoutRetryAfter(user);
            auditFailedRefresh(user.getId(), "Account locked");
            throw new AccountLockedException("Account locked. Try again in " + retryAfter + "s", retryAfter);
        }

        int revoked = refreshTokenRepository.revokeIfActive(stored.getTokenHash(), Instant.now());
        if (revoked == 0) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
            evictUserProfileCache(user.getId());
            auditLogService.logUserAction(user.getId(), "TOKEN_THEFT_DETECTED",
                    "Concurrent refresh on a single rotation -- all sessions invalidated");
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.AUTH_TOKEN_THEFT_DETECTED)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(user.getId().toString())
                    .result(AuditResult.DENIED)
                    .reason("Concurrent refresh on a single rotation -- all sessions invalidated")
                    .build());
            metrics.recordTokenTheftDetected();
            log.error("SECURITY: Refresh-token rotation race for user {}. All sessions revoked.", user.getId());
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }
        metrics.recordRefreshSuccess();
        auditLogService.logUserAction(user.getId(), "TOKEN_REFRESH", "Access token refreshed");
        return buildFullAuthResponse(user, httpRequest);
    }

    @Override
    @Transactional
    public void logout(String accessToken, UUID userId, String refreshToken) {
        blacklistToken(accessToken);
        if (refreshToken != null && refreshToken.length() >= 8) {
            revokeSingleRefreshToken(refreshToken, userId);
        } else {
            refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        }
        evictUserProfileCache(userId);
        auditLogService.logUserAction(userId, "LOGOUT", "User logged out (single device)");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_LOGOUT)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .metadata(java.util.Map.of("scope", "single-device"))
                .build());
    }

    @Override
    @Transactional
    public void logoutAllDevices(UUID userId, String accessToken) {
        blacklistToken(accessToken);
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        evictUserProfileCache(userId);
        auditLogService.logUserAction(userId, "LOGOUT_ALL", "User logged out from all devices");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_LOGOUT)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .metadata(java.util.Map.of("scope", "all-devices"))
                .build());
    }

    @Override
    public void blacklistToken(String accessToken) {
        if (accessToken == null) return;
        try {
            if (jwtTokenProvider.validateToken(accessToken)) {
                long ttlMs = jwtTokenProvider.extractExpiration(accessToken).getTime() - System.currentTimeMillis();
                if (ttlMs > 0) {
                    redisTemplate.opsForValue().set(
                            JWT_BLACKLIST_PREFIX + accessToken, "1", ttlMs, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            // SYSTEM 13 TASK 13.2: was a silent no-op -- if this write fails (e.g. Redis down),
            // the access token is never actually blacklisted and stays valid until natural expiry
            // with zero trace that revocation failed. Deliberately does NOT rethrow: this method
            // runs first inside logout()/logoutAllDevices()'s transaction, and the durable
            // revocation (refresh token DB row, revoked further down in those methods) matters
            // more than this ephemeral blacklist entry -- letting this exception propagate would
            // roll back the whole transaction and leave the user NOT logged out at all, which is
            // worse. Never logs the token itself (a live bearer credential).
            log.error("Failed to blacklist access token on logout -- token remains valid until "
                    + "natural expiry; refresh token revocation still proceeds", e);
            opsAlertService.alertJobFailure("RefreshTokenRotationServiceImpl.blacklistToken",
                    "access-token blacklist write failed, token stays valid until its own expiry", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthSessionResponse> listSessions(UUID userId, String currentDeviceId) {
        return refreshTokenRepository.findByUser_IdAndRevokedFalse(userId).stream()
                .filter(rt -> !rt.isExpired())
                .sorted(Comparator.comparing(RefreshToken::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(rt -> AuthSessionResponse.builder()
                        .id(rt.getId())
                        .deviceInfo(rt.getUserAgent() != null ? rt.getUserAgent() : rt.getDeviceInfo())
                        .ipAddress(rt.getIpAddress())
                        .geoCountry(rt.getGeoCountry())
                        .createdAt(rt.getCreatedAt())
                        .expiresAt(rt.getExpiresAt())
                        .anomalyFlagged(rt.isAnomalyFlagged())
                        .anomalyReason(rt.getAnomalyReason())
                        .current(currentDeviceId != null && currentDeviceId.equals(rt.getDeviceId()))
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public int revokeOtherSessions(UUID userId, String currentDeviceId) {
        int revoked;
        if (currentDeviceId == null || currentDeviceId.isBlank()) {
            List<AuthSessionResponse> before = listSessions(userId, null);
            refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
            revoked = before.size();
        } else {
            revoked = refreshTokenRepository.revokeAllByUserIdExceptDevice(
                    userId, currentDeviceId, Instant.now());
        }
        evictUserProfileCache(userId);
        auditLogService.logUserAction(userId, "SESSIONS_REVOKED_OTHERS",
                "Revoked " + revoked + " other session(s)");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_SESSION_REVOKED)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .metadata(java.util.Map.of("scope", "all-others", "count", String.valueOf(revoked)))
                .build());
        return revoked;
    }

    @Override
    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        RefreshToken token = refreshTokenRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));
        if (!token.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Session not found: " + sessionId);
        }
        if (!token.isRevoked()) {
            token.setRevoked(true);
            token.setRevokedAt(Instant.now());
            refreshTokenRepository.save(token);
            evictUserProfileCache(userId);
            auditLogService.logUserAction(userId, "SESSION_REVOKED", "Revoked session id=" + sessionId);
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.AUTH_SESSION_REVOKED)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(userId.toString())
                    .metadata(java.util.Map.of("sessionId", sessionId.toString()))
                    .build());
        }
    }

    // userId is the caller's own id (from their JWT) -- cross-checked here so logout can only
    // ever revoke the caller's own refresh tokens, never someone else's, even if a raw token
    // value were somehow supplied that belongs to another account.
    private void revokeSingleRefreshToken(String rawToken, UUID userId) {
        // Must match the 16-char prefix length used at token creation (line ~58) and in
        // rotate() (line ~90) -- this was previously 8, a silent mismatch that made the lookup
        // find zero rows and no-op, leaving the "revoked" refresh token fully usable forever.
        String prefix = rawToken.substring(0, Math.min(16, rawToken.length()));
        refreshTokenRepository.findByTokenPrefixAndRevokedFalse(prefix)
                .stream()
                .filter(rt -> rt.getUser().getId().equals(userId))
                .filter(rt -> passwordEncoder.matches(rawToken, rt.getTokenHash()))
                .findFirst()
                .ifPresent(rt -> refreshTokenRepository.revokeByTokenHash(rt.getTokenHash(), Instant.now()));
    }

    private long computeLockoutRetryAfter(User user) {
        if (user.getLockoutUntil() == null) return 0L;
        return Math.max(0L, java.time.Duration.between(Instant.now(), user.getLockoutUntil()).toSeconds());
    }

    private void evictUserProfileCache(UUID userId) {
        redisTemplate.delete(USER_PROFILE_CACHE_PREFIX + userId);
    }

    private boolean isOrganizationActive(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .map(org -> org.isActive() && org.getDeletedAt() == null)
                .orElse(true);
    }

    /** SYSTEM 08 TASK 8.4.b. AUTH_LOGIN_FAILED, not a refresh-specific action -- reusing the same
     *  taxonomy entry AuthServiceImpl.login() already uses for the equivalent rejection reasons
     *  (disabled/suspended/locked), since these represent the same underlying account states, just
     *  observed at refresh time instead of at login time. */
    private void auditFailedRefresh(UUID userId, String reason) {
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUTH_LOGIN_FAILED)
                .resourceType(AuditResourceType.USER)
                .resourceId(userId.toString())
                .result(AuditResult.DENIED)
                .reason(reason)
                .build());
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
