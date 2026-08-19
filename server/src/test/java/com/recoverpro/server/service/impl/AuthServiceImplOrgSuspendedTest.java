package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.LoginRequest;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.exception.OrganizationSuspendedException;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.MfaService;
import com.recoverpro.server.service.PasswordResetService;
import com.recoverpro.server.service.RefreshTokenRotationService;
import com.recoverpro.server.service.UserActionAuditService;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SYSTEM 18 TASK 18.2.b: a suspended organization must reject a fresh login, not just an
 *  already-issued token on its next request (that path is JwtAuthenticationFilterOrgSuspendedTest). */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplOrgSuspendedTest {

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
    private UUID orgId;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(userRepository, organizationRepository, refreshTokenRepository,
                passwordEncoder, rateLimiter, redisTemplate, new AppProperties(), userMapper,
                auditLogService, auditService, new ObjectMapper(), emailService, mfaService, passwordResetService,
                refreshTokenRotationService);
        orgId = UUID.randomUUID();
        lenient().when(rateLimiter.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void login_suspendedOrg_throwsAndNeverIssuesTokens() {
        User user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .passwordHash("hashed").organizationId(orgId).enabled(true).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true);
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(Organization.builder().id(orgId).isActive(false).build()));

        LoginRequest request = new LoginRequest();
        request.setEmail("agent@example.com");
        request.setPassword("Password123!");

        assertThatThrownBy(() -> service.login(request, httpRequest))
                .isInstanceOf(OrganizationSuspendedException.class);
        verify(refreshTokenRotationService, never()).buildFullAuthResponse(any(), any());
    }

    @Test
    void login_activeOrg_proceedsPastTheSuspensionCheck() {
        User user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .passwordHash("hashed").organizationId(orgId).enabled(true).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true);
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(Organization.builder().id(orgId).isActive(true).build()));

        LoginRequest request = new LoginRequest();
        request.setEmail("agent@example.com");
        request.setPassword("Password123!");

        // Reaches the real login flow past the suspension check (MFA disabled, not locked) and
        // calls through to token issuance -- the assertion is that it does NOT throw
        // OrganizationSuspendedException, not the full response shape.
        service.login(request, httpRequest);
        verify(refreshTokenRotationService).buildFullAuthResponse(any(), any());
    }
}
