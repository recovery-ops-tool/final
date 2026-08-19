package com.recoverpro.server.security.jwt;

import com.recoverpro.server.security.AccessDenialAuditor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 09 TASK 9.4.b: three rejections inside this filter short-circuit before Spring
 * Security's exception translation ever runs (they return directly instead of throwing or
 * calling the filter chain), so {@code RestAuthenticationEntryPoint} never sees them and its own
 * coverage doesn't prove anything about these paths. Without the audit call verified here
 * directly, a blacklisted-token replay or a token for a since-deleted user would be a 401 with no
 * audit trail at all -- the exact gap 9.4.b exists to close.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterUnauthorizedAuditTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserDetailsService userDetailsService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SseTicketService sseTicketService;
    @Mock private AccessDenialAuditor accessDenialAuditor;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(
                jwtTokenProvider, userDetailsService, redisTemplate, sseTicketService, accessDenialAuditor);
    }

    @Test
    void blacklistedToken_audits401AsUnauthorized() throws Exception {
        String token = "revoked.jwt.token";
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(accessDenialAuditor).recordUnauthorized(request, "Token has been revoked");
        verifyNoInteractions(filterChain);
    }

    @Test
    void tokenForDeletedUser_audits401AsUnauthorized() throws Exception {
        String token = "valid.but.orphaned.jwt";
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(false);
        when(jwtTokenProvider.extractUsername(token)).thenReturn("ghost@example.com");
        when(userDetailsService.loadUserByUsername("ghost@example.com"))
                .thenThrow(new UsernameNotFoundException("no such user"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(accessDenialAuditor).recordUnauthorized(request, "Invalid credentials");
    }

    @Test
    void invalidSseTicket_audits401AsUnauthorized() throws Exception {
        when(sseTicketService.redeemTicket("bad-ticket")).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        request.setParameter("ticket", "bad-ticket");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(accessDenialAuditor).recordUnauthorized(request, "Invalid or expired stream ticket");
        verifyNoInteractions(filterChain);
    }
}
