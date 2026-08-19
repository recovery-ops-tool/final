package com.recoverpro.server.security;

import com.recoverpro.server.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM 09 TASK 9.1.e: {@link OrgIsolationGuard#assertBelongsToOrg} is the throwing counterpart
 * added because 10 Lucien tool implementations called the boolean {@code belongsToOrg} as a bare
 * statement, discarding the result — dead code that enforced nothing. This proves the new method
 * actually enforces the check it's named for.
 */
class OrgIsolationGuardTest {

    private final OrgIsolationGuard guard = new OrgIsolationGuard();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assertBelongsToOrg_callerInDifferentOrg_throwsAccessDenied() {
        UUID ownOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateAs(ownOrg, "ROLE_MANAGER");

        assertThatThrownBy(() -> guard.assertBelongsToOrg(otherOrg))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assertBelongsToOrg_callerInSameOrg_doesNotThrow() {
        UUID orgId = UUID.randomUUID();
        authenticateAs(orgId, "ROLE_MANAGER");

        assertThatCode(() -> guard.assertBelongsToOrg(orgId)).doesNotThrowAnyException();
    }

    @Test
    void assertBelongsToOrg_platformAdmin_bypassesOrgCheck() {
        UUID ownOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateAs(ownOrg, "ROLE_PLATFORM_ADMIN");

        // Should not throw -- platform admin bypasses the org match entirely.
        guard.assertBelongsToOrg(otherOrg);
    }

    @Test
    void assertBelongsToOrg_unauthenticated_throwsAccessDenied() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> guard.assertBelongsToOrg(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticateAs(UUID organizationId, String role) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .roles(Set.of())
                .build();
        UserPrincipal principal = new UserPrincipal(user);
        Set<GrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
