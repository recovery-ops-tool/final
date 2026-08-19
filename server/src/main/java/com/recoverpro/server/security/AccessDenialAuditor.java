package com.recoverpro.server.security;

import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SYSTEM 09 TASK 9.4: single place every access-denial path (401 and 403 alike) goes through to
 * reach {@code unified_audit_events} -- keeps the throttle-then-record-then-never-let-a-write-
 * failure-break-the-response sequence in one spot instead of copied at each of the five call
 * sites that need it (RestAccessDeniedHandler, RestAuthenticationEntryPoint, and
 * JwtAuthenticationFilter's three short-circuit rejections). The audit write is always
 * best-effort: a broken write must never turn a clean 401/403 into a 500.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessDenialAuditor {

    private final AuditService auditService;
    private final DenialAuditThrottle denialAuditThrottle;

    /** 403: an authenticated caller's token was valid, but {@code @PreAuthorize} rejected the
     *  role/authority it carried. {@code actorUserId} may be null if the principal on the
     *  SecurityContext isn't a {@link UserPrincipal} (shouldn't happen in practice, since
     *  reaching this handler implies Spring Security resolved an Authentication, but the caller
     *  guards defensively rather than assume). */
    public void recordAccessDenied(HttpServletRequest request, UUID actorUserId) {
        recordAccessDenied(request, actorUserId, null);
    }

    /** Same as {@link #recordAccessDenied(HttpServletRequest, UUID)} but with a reason recorded
     *  in metadata -- e.g. SYSTEM 18 TASK 18.2.b's suspended-organization rejection, which is a
     *  403 like any other but worth distinguishing from an ordinary role/authority mismatch. */
    public void recordAccessDenied(HttpServletRequest request, UUID actorUserId, String reason) {
        String actorKey = actorUserId != null ? actorUserId.toString() : request.getRemoteAddr();
        record(AuditAction.ACCESS_DENIED, request, actorKey, reason,
                actorUserId != null ? actorUserId.toString() : null, null);
    }

    /** 401: no valid authentication reached the request at all -- missing/expired/blacklisted
     *  token, an invalid SSE stream ticket, or a token for a user record that no longer exists.
     *  {@code reason} distinguishes which of those it was (goes into metadata, not a separate
     *  AuditAction -- they're all the same category of event for query purposes). */
    public void recordUnauthorized(HttpServletRequest request, String reason) {
        record(AuditAction.AUTH_UNAUTHORIZED, request, request.getRemoteAddr(), reason,
                null, AuditActorType.ANONYMOUS);
    }

    private void record(AuditAction action, HttpServletRequest request, String actorKey,
                         String reason, String resourceId, AuditActorType actorTypeOverride) {
        try {
            String resourceKey = request.getMethod() + " " + request.getRequestURI();
            if (!denialAuditThrottle.shouldRecord(actorKey, resourceKey)) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("path", request.getRequestURI());
            metadata.put("method", request.getMethod());
            if (reason != null) {
                metadata.put("reason", reason);
            }
            auditService.record(AuditEventRequest.builder()
                    .action(action)
                    .resourceType(AuditResourceType.USER)
                    .resourceId(resourceId)
                    .result(AuditResult.DENIED)
                    .actorTypeOverride(actorTypeOverride)
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to audit {} for {} {}: {}",
                    action, request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }
}
