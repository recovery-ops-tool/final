package com.recoverpro.server.security;

import com.recoverpro.server.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * SYSTEM 09 TASK 9.4.a: every {@code @PreAuthorize} failure across the app funnels through this
 * handler -- confirms it both answers with a clean 403 and hands the denial off to {@link
 * AccessDenialAuditor} carrying the caller's identity, so the audit call site's own tests (which
 * verify the attempted resource actually lands in the recorded row) are exercised end-to-end from
 * where Spring Security really invokes this class.
 */
@ExtendWith(MockitoExtension.class)
class RestAccessDeniedHandlerTest {

    @Mock private AccessDenialAuditor accessDenialAuditor;

    private RestAccessDeniedHandler handler;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void handle_writesForbiddenBodyAndAuditsWithCallerId() throws Exception {
        handler = new RestAccessDeniedHandler(accessDenialAuditor);
        UUID callerId = UUID.randomUUID();
        authenticateAs(callerId, "ROLE_FO");
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/borrowers/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Access denied");
        verify(accessDenialAuditor).recordAccessDenied(request, callerId);
    }

    @Test
    void handle_noResolvablePrincipal_auditsWithNullUserId() throws Exception {
        handler = new RestAccessDeniedHandler(accessDenialAuditor);
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        verify(accessDenialAuditor).recordAccessDenied(request, null);
    }

    private static void authenticateAs(UUID userId, String role) {
        User user = User.builder().id(userId).organizationId(UUID.randomUUID()).roles(Set.of()).build();
        UserPrincipal principal = new UserPrincipal(user);
        Set<GrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
