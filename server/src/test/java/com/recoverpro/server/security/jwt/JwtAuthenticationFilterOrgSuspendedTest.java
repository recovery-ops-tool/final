package com.recoverpro.server.security.jwt;

import com.recoverpro.server.entity.User;
import com.recoverpro.server.security.AccessDenialAuditor;
import com.recoverpro.server.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 18 TASK 18.2.b: a suspended organization must block its users' NEXT request, not just
 * new logins. Covers the main request path (the SSE-ticket path is a smaller variant of the same
 * branch and is not separately exercised here).
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterOrgSuspendedTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserDetailsService userDetailsService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SseTicketService sseTicketService;
    @Mock private AccessDenialAuditor accessDenialAuditor;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;
    private static final String TOKEN = "some.valid.jwt";

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(
                jwtTokenProvider, userDetailsService, redisTemplate, sseTicketService, accessDenialAuditor);
        SecurityContextHolder.clearContext();
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtTokenProvider.extractUsername(TOKEN)).thenReturn("agent@example.com");
    }

    @Test
    void suspendedOrgUser_rejectedWith403AndNeverReachesFilterChain() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).email("agent@example.com")
                .organizationId(UUID.randomUUID()).enabled(true).build();
        when(userDetailsService.loadUserByUsername("agent@example.com"))
                .thenReturn(new UserPrincipal(user, false)); // organizationActive=false

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Organization Suspended");
        verifyNoInteractions(filterChain);
        verify(accessDenialAuditor).recordAccessDenied(request, userId, "Organization suspended");
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("must not populate the SecurityContext for a rejected request")
                .isNull();
    }

    @Test
    void activeOrgUser_passesThroughToFilterChain() throws Exception {
        User user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .organizationId(UUID.randomUUID()).enabled(true).build();
        when(userDetailsService.loadUserByUsername("agent@example.com"))
                .thenReturn(new UserPrincipal(user, true));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(accessDenialAuditor);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
