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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterBlacklistFailClosedTest {

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
    void redisUnavailableDuringBlacklistCheck_rejectsRequestInsteadOfAcceptingToken() throws Exception {
        String token = "some.valid.jwt";
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus())
                .as("a revocation check that can't be performed must fail closed, not silently accept the token")
                .isEqualTo(503);
        verifyNoInteractions(filterChain);
        verifyNoInteractions(userDetailsService);
    }
}
