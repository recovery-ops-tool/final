package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.dto.request.*;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.dto.response.MfaEnableResponse;
import com.recoverpro.server.dto.response.MfaSetupResponse;
import com.recoverpro.server.dto.response.AuthSessionResponse;
import com.recoverpro.server.dto.response.UserResponse;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuthService;
import com.recoverpro.server.util.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    private static final int      MFA_MAX_ATTEMPTS = 5;
    private static final Duration MFA_WINDOW        = Duration.ofMinutes(10);

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UserResponse response = authService.getCurrentUser(principal.getId());
        String etag = computeEtag(response);
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag).body(ApiResponse.success(response));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request, httpRequest)));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(authService.refreshToken(request, httpRequest)));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody(required = false) LogoutRequest body,
            HttpServletRequest request) {
        String token = extractBearerToken(request);
        String refreshToken = body != null ? body.getRefreshToken() : null;
        authService.logout(token, principal.getId(), refreshToken);
        return ResponseEntity.ok(ApiResponse.of("Logged out successfully", null));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {
        authService.logoutAllDevices(principal.getId(), extractBearerToken(request));
        return ResponseEntity.ok(ApiResponse.of("Logged out from all devices", null));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<AuthSessionResponse>>> listSessions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ResponseEntity.ok(ApiResponse.success(authService.listSessions(principal.getId(), deviceId)));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<Void>> revokeSession(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        authService.revokeSession(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.of("Session revoked", null));
    }

    // SYSTEM 08 TASK 8.2.c: "revoking all others" -- distinct from /logout-all, which also signs
    // the caller's own current session out.
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/sessions")
    public ResponseEntity<ApiResponse<Void>> revokeOtherSessions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        int revoked = authService.revokeOtherSessions(principal.getId(), deviceId);
        return ResponseEntity.ok(ApiResponse.of(revoked + " other session(s) revoked", null));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.<Void>of(
                "If the email is registered, a reset code has been sent", null));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/verify-reset-otp")
    public ResponseEntity<ApiResponse<Void>> verifyResetOtp(
            @Valid @RequestBody VerifyOtpRequest request) {
        authService.verifyResetOtp(request);
        return ResponseEntity.ok(ApiResponse.of("OTP verified", null));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.of("Password reset successful", null));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        authService.changePassword(principal.getId(), request, extractBearerToken(httpRequest));
        return ResponseEntity.ok(ApiResponse.of("Password changed successfully", null));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/mfa/setup")
    public ResponseEntity<ApiResponse<MfaSetupResponse>> setupMfa(
            @AuthenticationPrincipal UserPrincipal principal) {
        enforceMfaRateLimit("setup", principal.getId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(ApiResponse.success(authService.initMfaSetup(principal.getId())));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/mfa/enable")
    public ResponseEntity<ApiResponse<MfaEnableResponse>> enableMfa(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody EnableMfaRequest request) {
        enforceMfaRateLimit("enable", principal.getId());
        return ResponseEntity.ok(ApiResponse.success(authService.enableMfa(principal.getId(), request)));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/mfa/disable")
    public ResponseEntity<ApiResponse<Void>> disableMfa(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DisableMfaRequest request) {
        enforceMfaRateLimit("disable", principal.getId());
        authService.disableMfa(principal.getId(), request.getTotpCode());
        return ResponseEntity.ok(ApiResponse.of("MFA disabled successfully", null));
    }

    private void enforceMfaRateLimit(String action, UUID userId) {
        if (!rateLimiter.tryAcquire("rate:mfa:" + action + ":" + userId, MFA_MAX_ATTEMPTS, MFA_WINDOW)) {
            throw new RateLimitExceededException("Too many MFA attempts. Try again later.", MFA_WINDOW.toSeconds());
        }
    }

    private static String computeEtag(UserResponse u) {
        String roleNames = u.getRoles() == null ? "" :
                u.getRoles().stream().map(r -> r.getName()).sorted().collect(Collectors.joining(","));
        String key = u.getId() + "|" + u.isMfaEnabled() + "|" + u.isAccountLocked()
                + "|" + u.isEnabled() + "|" + roleNames;
        return "\"" + Integer.toHexString(key.hashCode() ^ (key.hashCode() >>> 16)) + "\"";
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
