package com.recoverpro.server.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Authorization checks for org-scoped endpoints. Reads from the already-loaded
 * UserPrincipal — zero DB hits per call.
 *
 * Usage in controllers: @PreAuthorize("@orgIsolationGuard.belongsToOrg(#orgId)")
 */
@Component("orgIsolationGuard")
public class OrgIsolationGuard {

    public boolean isPlatformAdmin() {
        return hasAuthority("ROLE_PLATFORM_ADMIN");
    }

    public boolean belongsToOrg(UUID organizationId) {
        if (isPlatformAdmin()) return true;
        UserPrincipal principal = currentPrincipal();
        if (principal == null) return false;
        return organizationId != null && organizationId.equals(principal.getOrganizationId());
    }

    /**
     * SYSTEM 09 TASK 9.1: throwing counterpart to {@link #belongsToOrg}, for call sites that
     * don't branch on the result — a boolean-returning check whose value is silently discarded
     * is dead code (confirmed in 10 Lucien tool implementations, all following the identical
     * bare-statement-call pattern; RLS backstopped every instance found, so none was an active
     * data leak, but the pattern is a trap for the next table that isn't RLS-covered). A method
     * that throws cannot be accidentally ignored the way one returning boolean can.
     */
    public void assertBelongsToOrg(UUID organizationId) {
        if (!belongsToOrg(organizationId)) {
            throw new AccessDeniedException(
                    "Caller does not belong to organization " + organizationId);
        }
    }

    public boolean hasRole(String roleName) {
        return hasAuthority(roleName);
    }

    /** Returns the current user's org ID, or null for platform admins. */
    public UUID currentOrganizationId() {
        UserPrincipal principal = currentPrincipal();
        return principal != null ? principal.getOrganizationId() : null;
    }

    /** Returns the current user's ID. */
    public UUID currentUserId() {
        UserPrincipal principal = currentPrincipal();
        return principal != null ? principal.getId() : null;
    }

    private boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return null;
        return principal;
    }
}
