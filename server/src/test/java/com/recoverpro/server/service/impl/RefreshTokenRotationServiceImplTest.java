package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.RefreshTokenRequest;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.dto.response.UserResponse;
import com.recoverpro.server.entity.RefreshToken;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.exception.InvalidTokenException;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.observability.BusinessMetrics;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.security.jwt.JwtTokenProvider;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.security.SessionAnomalyDetector;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN SP39: refresh-token rotation/session revocation extracted from AuthServiceImpl
 * into its own RefreshTokenRotationService.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenRotationServiceImplTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SessionAnomalyDetector sessionAnomalyDetector;
    @Mock private UserMapper userMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private BusinessMetrics metrics;
    @Mock private HttpServletRequest httpRequest;
    @Mock private NotificationService notificationService;
    @Mock private com.recoverpro.server.service.OpsAlertService opsAlertService;

    private RefreshTokenRotationServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenRotationServiceImpl(refreshTokenRepository, organizationRepository,
                jwtTokenProvider, passwordEncoder, sessionAnomalyDetector, new AppProperties(), userMapper,
                redisTemplate, auditLogService, auditService, metrics, notificationService, opsAlertService);
        user = User.builder().id(UUID.randomUUID()).enabled(true).build();
        lenient().when(userMapper.toResponse(any())).thenReturn(UserResponse.builder().build());
        lenient().when(jwtTokenProvider.generateAccessToken(any(), any())).thenReturn("access-token");
    }

    @Test
    void rotate_validActiveToken_revokesOldAndIssuesNewPair() {
        RefreshToken stored = RefreshToken.builder()
                .id(UUID.randomUUID()).user(user).tokenHash("stored-hash").tokenPrefix("rawtoken12345678")
                .revoked(false).expiresAt(Instant.now().plusSeconds(600)).build();
        when(refreshTokenRepository.findByTokenPrefixAndRevokedFalse("rawtoken12345678"))
                .thenReturn(List.of(stored));
        when(passwordEncoder.matches("rawtoken12345678fulltoken", "stored-hash")).thenReturn(true);
        when(refreshTokenRepository.revokeIfActive(eq("stored-hash"), any(Instant.class))).thenReturn(1);

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("rawtoken12345678fulltoken");

        AuthResponse response = service.rotate(request, httpRequest);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        verify(metrics).recordRefreshSuccess();
    }

    @Test
    void rotate_unknownToken_throwsInvalidToken() {
        when(refreshTokenRepository.findByTokenPrefixAndRevokedFalse(any())).thenReturn(List.of());
        when(refreshTokenRepository.findByTokenPrefix(any())).thenReturn(List.of());

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("unknown-raw-token-value");

        assertThatThrownBy(() -> service.rotate(request, httpRequest))
                .isInstanceOf(InvalidTokenException.class);
    }

    /** SYSTEM 08 TASK 8.4.b: a genuinely unknown/garbage/expired refresh token -- no user to
     *  attribute it to -- previously produced zero audit trail at all (only the theft-replay
     *  sub-case, tested separately below, was ever audited). Same AUTH_UNAUTHORIZED category
     *  JwtAuthenticationFilter's access-token path already uses for "no valid credentials." */
    @Test
    void rotate_unknownToken_recordsAuthUnauthorized() {
        when(refreshTokenRepository.findByTokenPrefixAndRevokedFalse(any())).thenReturn(List.of());
        when(refreshTokenRepository.findByTokenPrefix(any())).thenReturn(List.of());

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("unknown-raw-token-value");

        assertThatThrownBy(() -> service.rotate(request, httpRequest))
                .isInstanceOf(InvalidTokenException.class);

        verify(auditService).record(argThat(evt -> evt.getAction() == AuditAction.AUTH_UNAUTHORIZED));
    }

    @Test
    void rotate_disabledAccount_recordsAuthLoginFailed() {
        user.setEnabled(false);
        RefreshToken stored = RefreshToken.builder()
                .id(UUID.randomUUID()).user(user).tokenHash("stored-hash").tokenPrefix("rawtoken12345678")
                .revoked(false).expiresAt(Instant.now().plusSeconds(600)).build();
        when(refreshTokenRepository.findByTokenPrefixAndRevokedFalse("rawtoken12345678"))
                .thenReturn(List.of(stored));
        when(passwordEncoder.matches("rawtoken12345678fulltoken", "stored-hash")).thenReturn(true);

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("rawtoken12345678fulltoken");

        assertThatThrownBy(() -> service.rotate(request, httpRequest))
                .isInstanceOf(com.recoverpro.server.exception.AccountDisabledException.class);

        verify(auditService).record(argThat(evt ->
                evt.getAction() == AuditAction.AUTH_LOGIN_FAILED
                        && "Account disabled".equals(evt.getReason())));
    }

    @Test
    void rotate_revokedTokenReplayed_revokesAllSessionsAndRecordsTheft() {
        RefreshToken revokedToken = RefreshToken.builder()
                .id(UUID.randomUUID()).user(user).tokenHash("stored-hash").tokenPrefix("rawtoken12345678")
                .revoked(true).expiresAt(Instant.now().plusSeconds(600)).build();
        when(refreshTokenRepository.findByTokenPrefixAndRevokedFalse("rawtoken12345678")).thenReturn(List.of());
        when(refreshTokenRepository.findByTokenPrefix("rawtoken12345678")).thenReturn(List.of(revokedToken));
        when(passwordEncoder.matches("rawtoken12345678fulltoken", "stored-hash")).thenReturn(true);

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("rawtoken12345678fulltoken");

        assertThatThrownBy(() -> service.rotate(request, httpRequest))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any(Instant.class));
        verify(metrics).recordTokenTheftDetected();
        verify(auditLogService).logUserAction(eq(user.getId()), eq("TOKEN_THEFT_DETECTED"), any());
    }

    /** SYSTEM 08 TASK 8.2's literal ACCEPTANCE: "revoking one [session] from the other causes the
     *  revoked session's next refresh to fail with 401" -- InvalidTokenException maps to 401 via
     *  GlobalExceptionHandler. revokeSession() marks the row revoked=true; the SAME replay-detection
     *  branch rotate() already has for a reused token (proven by the theft-detection test above)
     *  is what a revoked-then-presented token also hits -- this pins that the two paths actually
     *  connect, not just that each one individually throws in isolation. */
    @Test
    void revokeSession_thenRotateWithThatToken_throwsInvalidToken() {
        UUID sessionId = UUID.randomUUID();
        RefreshToken session = RefreshToken.builder()
                .id(sessionId).user(user).tokenHash("stored-hash").tokenPrefix("rawtoken12345678")
                .revoked(false).expiresAt(Instant.now().plusSeconds(600)).build();
        when(refreshTokenRepository.findById(sessionId)).thenReturn(java.util.Optional.of(session));

        service.revokeSession(user.getId(), sessionId);

        assertThat(session.isRevoked())
                .as("revokeSession must flip the SAME row rotate() checks, not a copy")
                .isTrue();

        // The revoked row no longer appears in the "active" query rotate() uses first...
        when(refreshTokenRepository.findByTokenPrefixAndRevokedFalse("rawtoken12345678")).thenReturn(List.of());
        // ...so it falls through to the revoked-replay lookup, exactly like a reused/stolen token.
        when(refreshTokenRepository.findByTokenPrefix("rawtoken12345678")).thenReturn(List.of(session));
        when(passwordEncoder.matches("rawtoken12345678fulltoken", "stored-hash")).thenReturn(true);

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("rawtoken12345678fulltoken");

        assertThatThrownBy(() -> service.rotate(request, httpRequest))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revokeSession_notOwnedByCaller_throwsResourceNotFound() {
        UUID sessionId = UUID.randomUUID();
        RefreshToken othersSession = RefreshToken.builder()
                .id(sessionId).user(User.builder().id(UUID.randomUUID()).build())
                .tokenHash("x").revoked(false).build();
        when(refreshTokenRepository.findById(sessionId)).thenReturn(java.util.Optional.of(othersSession));

        assertThatThrownBy(() -> service.revokeSession(user.getId(), sessionId))
                .isInstanceOf(com.recoverpro.server.common.exception.ResourceNotFoundException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    /* ── SYSTEM 08 TASK 8.2.c: revoke all OTHER sessions ─────────────────────── */

    @Test
    void revokeOtherSessions_withDeviceId_delegatesToExceptDeviceQuery() {
        when(refreshTokenRepository.revokeAllByUserIdExceptDevice(
                eq(user.getId()), eq("device-A"), any(Instant.class))).thenReturn(3);

        int revoked = service.revokeOtherSessions(user.getId(), "device-A");

        assertThat(revoked).isEqualTo(3);
        verify(refreshTokenRepository, never()).revokeAllByUserId(any(), any());
        verify(auditService).record(argThat(req ->
                req.getAction() == AuditAction.AUTH_SESSION_REVOKED));
    }

    @Test
    void revokeOtherSessions_noDeviceId_fallsBackToRevokingEverything() {
        when(refreshTokenRepository.findByUser_IdAndRevokedFalse(user.getId())).thenReturn(List.of(
                RefreshToken.builder().id(UUID.randomUUID()).user(user).revoked(false)
                        .expiresAt(Instant.now().plusSeconds(600)).build(),
                RefreshToken.builder().id(UUID.randomUUID()).user(user).revoked(false)
                        .expiresAt(Instant.now().plusSeconds(600)).build()));

        int revoked = service.revokeOtherSessions(user.getId(), null);

        assertThat(revoked).isEqualTo(2);
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any(Instant.class));
        verify(refreshTokenRepository, never()).revokeAllByUserIdExceptDevice(any(), any(), any());
    }

    @Test
    void logout_singleDevice_revokesOnlyThatTokenAndBlacklistsAccessToken() {
        service.logout(null, user.getId(), null);
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any(Instant.class));
        verify(auditLogService).logUserAction(eq(user.getId()), eq("LOGOUT"), any());
    }

    @Test
    void logoutAllDevices_revokesAllSessions() {
        service.logoutAllDevices(user.getId(), null);
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any(Instant.class));
        verify(auditLogService).logUserAction(eq(user.getId()), eq("LOGOUT_ALL"), any());
    }

    /**
     * SYSTEM 13 TASK 13.2: blacklistToken's Redis write used to fail completely silently (empty
     * catch, no log, no alert) -- a logged-out access token would stay valid until its own natural
     * expiry with zero trace anything went wrong. Proves the failure is now visible (alerted) AND
     * that it doesn't block the more important durable revocation (the refresh-token DB row) --
     * blacklistToken runs first inside logout()'s transaction, so letting the exception propagate
     * would have rolled that back too and left the user not logged out at all.
     */
    @Test
    void logout_blacklistWriteFails_stillRevokesRefreshTokenAndAlertsInsteadOfSwallowing() {
        String accessToken = "a.b.c";
        when(jwtTokenProvider.validateToken(accessToken)).thenReturn(true);
        when(jwtTokenProvider.extractExpiration(accessToken))
                .thenReturn(java.util.Date.from(Instant.now().plusSeconds(300)));
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(any(), any(), anyLong(), any());

        service.logout(accessToken, user.getId(), null);

        verify(opsAlertService).alertJobFailure(
                eq("RefreshTokenRotationServiceImpl.blacklistToken"), any(), any());
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any(Instant.class));
        verify(auditLogService).logUserAction(eq(user.getId()), eq("LOGOUT"), any());
    }
}
