package com.recoverpro.server.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * SYSTEM 09 TASK 9.4.b: before this task, a request with no valid authentication got a 401 and
 * left zero trace in the audit table -- this proves that gap is closed by confirming the entry
 * point both answers with a clean 401 and hands the denial off to {@link AccessDenialAuditor},
 * the same way {@link RestAccessDeniedHandler} does for 403s (see {@link
 * AccessDenialAuditorTest#accessDeniedAndUnauthorized_useDifferentAuditActions} for proof the two
 * paths land on different AuditAction values).
 */
@ExtendWith(MockitoExtension.class)
class RestAuthenticationEntryPointTest {

    @Mock private AccessDenialAuditor accessDenialAuditor;

    @Test
    void commence_writesUnauthorizedBodyAndAuditsTheAttempt() throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(accessDenialAuditor);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Authentication required");
        verify(accessDenialAuditor).recordUnauthorized(request, "no valid authentication");
    }
}
