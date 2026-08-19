package com.recoverpro.server.service;

import com.recoverpro.server.dto.request.RefreshTokenRequest;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.dto.response.AuthSessionResponse;
import com.recoverpro.server.entity.User;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

/** Session issuance/rotation/revocation (access + refresh tokens), split out of
 * AuthServiceImpl (SYSTEM-PLAN SP39). */
public interface RefreshTokenRotationService {

    /** Issues a fresh access+refresh token pair for an already-authenticated user
     * (successful register/login/refresh). */
    AuthResponse buildFullAuthResponse(User user, HttpServletRequest request);

    /** Validates and rotates a refresh token, detecting reuse/replay of a revoked token. */
    AuthResponse rotate(RefreshTokenRequest request, HttpServletRequest httpRequest);

    void logout(String accessToken, UUID userId, String refreshToken);

    void logoutAllDevices(UUID userId, String accessToken);

    void blacklistToken(String accessToken);

    /** Lists a user's non-revoked, non-expired sessions, newest first. currentDeviceId (may be
     * null) marks the session matching this request's X-Device-Id as "current". */
    List<AuthSessionResponse> listSessions(UUID userId, String currentDeviceId);

    /** Revokes one specific session by id. Throws if it doesn't belong to userId. */
    void revokeSession(UUID userId, UUID sessionId);

    /** SYSTEM 08 TASK 8.2.c: revokes every OTHER session, leaving the caller's own current one
     *  (identified by currentDeviceId, from X-Device-Id) intact. If currentDeviceId is null (the
     *  caller sent no device id, so "current" can't be identified), falls back to revoking
     *  everything -- the same as {@link #logoutAllDevices}, since there is no session to spare.
     *  Returns the number of sessions revoked. */
    int revokeOtherSessions(UUID userId, String currentDeviceId);
}
