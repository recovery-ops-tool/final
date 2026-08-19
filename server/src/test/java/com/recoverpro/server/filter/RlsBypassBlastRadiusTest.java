package com.recoverpro.server.filter;

import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.RlsOrgIdHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM 01 TASK 1.3: proves the platform-admin RLS bypass flag cannot leak across requests on a
 * pooled thread, even when the request that set it throws. This is the same real production
 * classes as the request path -- {@link RlsContextFilter#doFilterInternal} called directly (it's
 * {@code protected}, hence this test living in its package) around a real
 * {@link PlatformAdminAccessGuard#beginCrossOrgAccess}/{@code beginUnattendedCrossOrgAccess} call
 * that sets {@link RlsOrgIdHolder}'s bypass flag -- not a re-implementation of the clearing logic
 * that could drift from what actually runs in production.
 * <p>
 * {@code PlatformAdminAccessGuard}'s own audit-writing dependencies are mocked (this test asserts
 * ThreadLocal clearing, not audit persistence -- that's {@code AuditServiceImpl}'s own test
 * surface), but the guard class itself is real, so its actual call to the package-private
 * {@link RlsOrgIdHolder#setBypass} runs for real.
 */
@ExtendWith(MockitoExtension.class)
class RlsBypassBlastRadiusTest {

    @Mock private com.recoverpro.server.service.UserActionAuditService userActionAuditService;
    @Mock private com.recoverpro.server.service.AuditService auditService;

    private final RlsContextFilter rlsContextFilter = new RlsContextFilter();
    private PlatformAdminAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PlatformAdminAccessGuard(userActionAuditService, auditService);
    }

    @AfterEach
    void clearEverything() {
        SecurityContextHolder.clearContext();
        RlsOrgIdHolder.clear();
    }

    @Test
    void beginCrossOrgAccess_bypassClearedEvenWhenRequestThrows() {
        UUID adminId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        FilterChain throwingChain = (req, res) -> {
            guard.beginCrossOrgAccess(adminId, targetOrgId, "support-ticket-123", "AllocationController");
            assertThat(RlsOrgIdHolder.isBypass())
                    .as("bypass must actually be set mid-request for this test to mean anything")
                    .isTrue();
            throw new RuntimeException("simulated failure mid cross-org read");
        };

        assertThatThrownBy(() -> rlsContextFilter.doFilterInternal(
                new MockHttpServletRequest(), new MockHttpServletResponse(), throwingChain))
                .hasMessageContaining("simulated failure");

        assertBypassFullyClearedOnThisThread();
    }

    @Test
    void beginUnattendedCrossOrgAccess_bypassClearedEvenWhenRequestThrows() {
        UUID adminId = UUID.randomUUID();

        FilterChain throwingChain = (req, res) -> {
            guard.beginUnattendedCrossOrgAccess(adminId, "SosAudioWebSocketHandler");
            assertThat(RlsOrgIdHolder.isBypass()).isTrue();
            throw new RuntimeException("simulated failure mid unattended cross-org read");
        };

        assertThatThrownBy(() -> rlsContextFilter.doFilterInternal(
                new MockHttpServletRequest(), new MockHttpServletResponse(), throwingChain))
                .hasMessageContaining("simulated failure");

        assertBypassFullyClearedOnThisThread();
    }

    /**
     * Literal acceptance wording from the task: "a subsequent unrelated query on the SAME THREAD
     * is back under normal org scoping." {@link RlsOrgIdHolder#get()} returning null after the
     * throw is exactly that -- the next connection checkout on this thread stamps no org id and no
     * bypass GUC, so it falls back to strict, fail-closed RLS rather than inheriting either the
     * failed request's target org or its elevated privilege.
     */
    private void assertBypassFullyClearedOnThisThread() {
        assertThat(RlsOrgIdHolder.isBypass())
                .as("a leaked bypass flag on a pooled request thread is a total tenancy failure")
                .isFalse();
        assertThat(RlsOrgIdHolder.get())
                .as("org id must also be cleared, not just the bypass flag")
                .isNull();
    }

    @Test
    void normalRequest_setsOrgFromPrincipalAndClearsItAfter() {
        UUID orgId = UUID.randomUUID();
        var principal = org.mockito.Mockito.mock(com.recoverpro.server.security.UserPrincipal.class);
        org.mockito.Mockito.when(principal.getOrganizationId()).thenReturn(orgId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));

        FilterChain chain = (req, res) ->
                assertThat(RlsOrgIdHolder.get()).isEqualTo(orgId);

        org.assertj.core.api.Assertions.assertThatCode(() -> rlsContextFilter.doFilterInternal(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();

        assertThat(RlsOrgIdHolder.get()).as("must be cleared after the request completes").isNull();
    }
}
