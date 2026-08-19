package com.recoverpro.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.AuditEventFilterRequest;
import com.recoverpro.server.dto.response.AuditEventResponse;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventQueryService;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SYSTEM 10 TASK 10.4: auditor read/export surface over {@code unified_audit_events}. Deliberately
 * a separate controller from {@link AuditLogController}, which serves the five older, narrower
 * per-domain audit tables (assignment/collection/ptp/settlement/allocation/user-action) for
 * case-timeline UIs -- this one is the cross-cutting security/compliance view.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
@Slf4j
public class AuditEventController {

    private final AuditEventQueryService auditEventQueryService;
    private final AuditService auditService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;
    private final ObjectMapper objectMapper;

    @GetMapping
    @PreAuthorize(Authz.ADMINS)
    public ResponseEntity<ApiResponse<PagedResponse<AuditEventResponse>>> search(
            @AuthenticationPrincipal UserPrincipal principal,
            AuditEventFilterRequest filter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (from != null) filter.setFrom(from);
        if (to != null) filter.setTo(to);

        UUID orgIdOverride = resolveOrgIdOverride(principal, orgId, reason, "audit-events:list");
        Page<AuditEventResponse> result = auditEventQueryService.search(
                filter, orgIdOverride, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/export")
    @PreAuthorize(Authz.ADMINS)
    public ResponseEntity<StreamingResponseBody> export(
            @AuthenticationPrincipal UserPrincipal principal,
            AuditEventFilterRequest filter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "csv") String format) {

        if (from != null) filter.setFrom(from);
        if (to != null) filter.setTo(to);

        UUID orgIdOverride = resolveOrgIdOverride(principal, orgId, reason, "audit-events:export");
        List<AuditEventResponse> rows =
                auditEventQueryService.search(filter, orgIdOverride, Pageable.unpaged()).getContent();

        // TASK 10.4.c: the export itself must be audited, naming the exporter and what was
        // exported -- AuditAction.AUDIT_LOG_EXPORTED existed unused since V085 for exactly this.
        // organizationIdOverride is non-null only on the platform-admin path (orgIdOverride); for
        // an ORG_ADMIN it's null so AuditServiceImpl falls back to RlsOrgIdHolder.get(), which
        // already resolves to their own org for an ordinary request -- so their own org's audit
        // trail records the export too, not just the platform-admin's cross-org access log.
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.AUDIT_LOG_EXPORTED)
                .resourceType(AuditResourceType.AUDIT_LOG)
                .organizationIdOverride(orgIdOverride)
                .metadata(Map.of("format", format, "rowCount", rows.size()))
                .build());

        boolean json = "json".equalsIgnoreCase(format);
        StreamingResponseBody body = json ? jsonBody(rows) : csvBody(rows);
        String filename = "audit-events." + (json ? "json" : "csv");

        return ResponseEntity.ok()
                .contentType(json ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private StreamingResponseBody jsonBody(List<AuditEventResponse> rows) {
        return out -> objectMapper.writeValue(out, rows);
    }

    private StreamingResponseBody csvBody(List<AuditEventResponse> rows) {
        return out -> {
            try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write("Timestamp,Action,ResourceType,ResourceId,ActorUserId,ActorName,ActorRole,"
                        + "Severity,Result,Source,IPAddress,RequestId,Reason\n");
                for (AuditEventResponse r : rows) {
                    w.write(csvJoin(r.getCreatedAt(), r.getAction(), r.getResourceType(), r.getResourceId(),
                            r.getActorUserId(), r.getActorName(), r.getActorRole(), r.getSeverity(),
                            r.getResult(), r.getSource(), r.getIpAddress(), r.getRequestId(), r.getReason()));
                }
                w.flush();
            }
        };
    }

    /**
     * SYSTEM 10 TASK 10.4.b: RLS ({@code rls_unified_audit_events_isolation}, V085) already scopes
     * an ordinary org-scoped caller's query to {@code current_org_id()} -- returning {@code null}
     * here (rather than {@code principal.getOrganizationId()}) means no app-level org predicate is
     * ever added for that caller, avoiding the exact "duplicate the control" trap the task warned
     * about. A platform admin has no {@code current_org_id()} of their own, so they must name a
     * target org (and a reason, matching {@code ReportingController}/{@code VisitSessionController}'s
     * existing attributable-elevation pattern) -- {@link PlatformAdminAccessGuard#beginCrossOrgAccess}
     * both flips the RLS bypass and records that this admin looked. The returned org id is then
     * used purely as a query-level filter on top of that already-attributable elevation, since the
     * bypass itself has no per-org boundary and would otherwise mix every tenant's events together.
     */
    private UUID resolveOrgIdOverride(UserPrincipal principal, UUID requestedOrgId, String reason, String resource) {
        if (isPlatformAdmin(principal)) {
            if (requestedOrgId == null) {
                throw new BusinessException(
                        "Platform admins must specify ?orgId= (and ?reason=) to view a tenant's audit events.");
            }
            platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), requestedOrgId, reason, resource);
            return requestedOrgId;
        }
        return null;
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private static String csvJoin(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(fields[i]));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String csvEscape(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
