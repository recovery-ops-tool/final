package com.recoverpro.server.security;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.UserActionAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single sanctioned way to elevate a request past tenant RLS isolation.
 *
 * <p>Platform admins legitimately need to read a tenant's data for support, but in a regulated
 * lending context that access has to be attributable: who looked, whose data, and why. Routing
 * every elevation through here means no caller can reach {@link RlsOrgIdHolder#setBypass} (it's
 * package-private) without going through the audit write this class performs first in call-graph
 * terms -- no business-data read anywhere in the app can happen until this method returns, and
 * both writes below happen before that return either way.
 *
 * <p>SYSTEM 10 TASK 10.4 (found via a real integration test, not a mock): {@link #beginCrossOrgAccess}
 * used to call {@code setBypass(true)} strictly after {@code auditService.record(...)}, in the
 * belief that "audit before bypass" required that literal statement order. It doesn't -- the actual
 * invariant is that nothing outside this class can activate the bypass without this class's audit
 * call having already run, which holds regardless of the two lines' order inside one trusted,
 * unconditional method body. But the literal order mattered anyway, for an unrelated reason: the
 * {@code unified_audit_events} row this writes carries {@code organizationId = targetOrgId} (a
 * real, different org, by design -- that's the whole point of recording *which* tenant was
 * accessed), and {@code RlsAwareDataSource} stamps {@code app.is_platform_admin} onto a connection
 * only at checkout, read from {@code RlsOrgIdHolder.isBypass()} at that instant. Since
 * {@code auditService.record} runs in its own {@code REQUIRES_NEW} transaction (a fresh checkout),
 * calling it before {@code setBypass(true)} meant the bypass was never active for that checkout --
 * so the INSERT's own {@code WITH CHECK} (satisfied only by a matching org, a NULL org, or the
 * bypass) rejected every real cross-org access attempt with "new row violates row-level security
 * policy," 100% of the time, for as long as this class has existed. {@link #beginUnattendedCrossOrgAccess}
 * never hit this: it leaves {@code organizationId} NULL (no target org is known yet), which the
 * {@code WITH CHECK}'s NULL-org branch already allows unconditionally.
 *
 * <p>The audit write runs in its own transaction (see
 * {@code AuditLogServiceImpl#logUserAction}, REQUIRES_NEW), so a request that later fails and
 * rolls back still leaves the access on record.
 */
@Component
@RequiredArgsConstructor
public class PlatformAdminAccessGuard {

    /** Action name recorded in user_action_audit_logs for every cross-tenant read. */
    public static final String CROSS_ORG_ACCESS_ACTION = "PLATFORM_ADMIN_CROSS_ORG_ACCESS";

    private final UserActionAuditService userActionAuditService;
    private final AuditService auditService;

    /**
     * Records the access, then elevates this thread past tenant RLS for the rest of the request.
     *
     * <p>SYSTEM 10 TASK 10.4: {@code setBypass(true)} runs before {@code auditService.record} here
     * (not after, despite the class javadoc's older framing) because the record call itself needs
     * it -- see the class javadoc for the full mechanism. Safe to do in this order: nothing between
     * here and this method's return can read another tenant's actual business data, so the bypass
     * being active one statement earlier doesn't create any new window of unlogged access.
     *
     * @param adminUserId the platform admin doing the looking -- never a tenant user
     * @param targetOrgId the organization whose data is about to be read
     * @param reason      the caller's stated justification (ticket ref, support case)
     */
    public void beginCrossOrgAccess(UUID adminUserId, UUID targetOrgId, String reason, String resource) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    "Cross-organization access requires a stated reason. Pass ?reason= with the "
                            + "ticket or support case justifying access to this tenant's data.");
        }
        userActionAuditService.logUserAction(
                adminUserId,
                CROSS_ORG_ACCESS_ACTION,
                "resource=" + resource + "; targetOrganizationId=" + targetOrgId + "; reason=" + reason);
        RlsOrgIdHolder.setBypass(true);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.CROSS_ORG_ACCESS)
                .resourceType(AuditResourceType.ORGANIZATION)
                .resourceId(targetOrgId.toString())
                .reason(reason)
                .actorUserIdOverride(adminUserId)
                .actorTypeOverride(AuditActorType.USER)
                .organizationIdOverride(targetOrgId)
                .metadata(java.util.Map.of("resource", resource))
                .build());
    }

    /**
     * Elevation for non-interactive paths that have no way to collect a reason from the caller.
     *
     * <p>Currently only the SOS audio websocket: the subscribe frame carries no justification
     * field, the target org is unknowable until after the incident lookup that needs the
     * elevation, and demanding a typed justification mid-emergency would add friction to a
     * safety feature. The access is still recorded, and still recorded <em>before</em> any row
     * is read.
     *
     * <p>Whether a supervisor should have to justify listening to another tenant's emergency
     * audio is a product and compliance decision rather than a technical one. If that answer is
     * "yes", this method should be deleted and the callers moved onto
     * {@link #beginCrossOrgAccess}.
     */
    public void beginUnattendedCrossOrgAccess(UUID adminUserId, String resource) {
        userActionAuditService.logUserAction(
                adminUserId,
                CROSS_ORG_ACCESS_ACTION,
                "resource=" + resource + "; reason=<none: non-interactive path>");
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.CROSS_ORG_ACCESS)
                .resourceType(AuditResourceType.ORGANIZATION)
                .reason("<none: non-interactive path>")
                .actorUserIdOverride(adminUserId)
                .actorTypeOverride(AuditActorType.USER)
                .metadata(java.util.Map.of("resource", resource))
                .build());
        RlsOrgIdHolder.setBypass(true);
    }
}
