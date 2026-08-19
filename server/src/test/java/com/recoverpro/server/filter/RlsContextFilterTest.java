package com.recoverpro.server.filter;

import com.recoverpro.server.entity.User;
import com.recoverpro.server.security.RlsOrgIdHolder;
import com.recoverpro.server.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 13 TASK 13.1's prerequisite (SYSTEM 11 "MDC context") named orgId/userId in MDC as
 * already done -- it wasn't (see this class's own javadoc). Proves the gap is actually closed:
 * both keys are set during the request and cleared afterward, including on the
 * authenticated-but-something-throws path (MDC leaking a stale org/user id onto the next request
 * handled by the same pooled thread would be a real cross-tenant data leak in every downstream
 * log line and, once TASK 13.1 wires an error tracker, error event too).
 */
class RlsContextFilterTest {

    private final RlsContextFilter filter = new RlsContextFilter();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        RlsOrgIdHolder.clear();
        MDC.clear();
    }

    @Test
    void authenticatedRequest_putsOrgAndUserIdInMdcDuringTheRequest() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        authenticateAs(userId, orgId);

        FilterChain chain = (req, res) -> {
            assertThat(MDC.get(RlsContextFilter.ORG_ID_MDC_KEY)).isEqualTo(orgId.toString());
            assertThat(MDC.get(RlsContextFilter.USER_ID_MDC_KEY)).isEqualTo(userId.toString());
            assertThat(RlsOrgIdHolder.get()).isEqualTo(orgId);
        };

        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }

    @Test
    void afterRequestCompletes_mdcAndRlsHolderAreCleared() throws Exception {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID());
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(MDC.get(RlsContextFilter.ORG_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(RlsContextFilter.USER_ID_MDC_KEY)).isNull();
        assertThat(RlsOrgIdHolder.get()).isNull();
    }

    @Test
    void downstreamThrows_stillClearsMdcAndRlsHolder() {
        authenticateAs(UUID.randomUUID(), UUID.randomUUID());
        FilterChain chain = (req, res) -> { throw new RuntimeException("boom"); };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), chain));

        assertThat(MDC.get(RlsContextFilter.ORG_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(RlsContextFilter.USER_ID_MDC_KEY)).isNull();
        assertThat(RlsOrgIdHolder.get()).isNull();
    }

    @Test
    void unauthenticatedRequest_neverSetsMdc() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(MDC.get(RlsContextFilter.ORG_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(RlsContextFilter.USER_ID_MDC_KEY)).isNull();
    }

    private static void authenticateAs(UUID userId, UUID organizationId) {
        User user = User.builder().id(userId).organizationId(organizationId).roles(Set.of()).build();
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Set.of()));
    }
}
