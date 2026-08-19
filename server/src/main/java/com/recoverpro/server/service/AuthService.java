package com.recoverpro.server.service;

import com.recoverpro.server.dto.request.*;
import com.recoverpro.server.dto.response.AuthResponse;
import com.recoverpro.server.dto.response.MfaEnableResponse;
import com.recoverpro.server.dto.response.MfaSetupResponse;
import com.recoverpro.server.dto.response.AuthSessionResponse;
import com.recoverpro.server.dto.response.UserResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

public interface AuthService {

    UserResponse getCurrentUser(UUID userId);

    AuthResponse login(LoginRequest request, HttpServletRequest httpRequest);

    AuthResponse refreshToken(RefreshTokenRequest request, HttpServletRequest httpRequest);

    void logout(String accessToken, UUID userId, String refreshToken);

    void logoutAllDevices(UUID userId, String accessToken);

    List<AuthSessionResponse> listSessions(UUID userId, String currentDeviceId);

    void revokeSession(UUID userId, UUID sessionId);

    int revokeOtherSessions(UUID userId, String currentDeviceId);

    void forgotPassword(ForgotPasswordRequest request);

    void verifyResetOtp(VerifyOtpRequest request);

    void resetPassword(ResetPasswordRequest request);

    void changePassword(UUID userId, ChangePasswordRequest request, String accessToken);

    MfaSetupResponse initMfaSetup(UUID userId);

    MfaEnableResponse enableMfa(UUID userId, EnableMfaRequest request);

    void disableMfa(UUID userId, String totpCode);
}
