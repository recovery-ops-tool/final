package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only KPI surface backed by the {@code v_kpi_*} views (V071; design-doc §11.3 originally
 * called for these under a "V34" that never shipped).
 *
 * Each endpoint returns a list of map rows so the client tier can render whatever shape the
 * dashboard wants. The view layer keeps the SQL on the DB side and the endpoint logic trivial;
 * when KPI volume grows, swap a view for a materialised view + scheduled refresh.
 *
 * v_kpi_complaint_rate / v_kpi_grievance_mttr are the two of the original 8 still missing --
 * both need grievance data, and grievances have zero application code anywhere yet.
 */
@RestController
@RequestMapping("/api/v1/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final JdbcTemplate jdbc;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    private static final String ADMINS = Authz.ADMINS;

    @GetMapping("/collection-efficiency")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> collectionEfficiency(
            @RequestParam UUID organizationId, @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = resolveOrgId(principal, organizationId, reason, "kpi:collectionEfficiency");
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_collection_efficiency WHERE organization_id = ? "
                        + "ORDER BY bucket_month",
                orgId));
    }

    @GetMapping("/roll-forward-rate")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> rollForwardRate(
            @RequestParam UUID organizationId, @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = resolveOrgId(principal, organizationId, reason, "kpi:rollForwardRate");
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_roll_forward_rate WHERE organization_id = ? "
                        + "ORDER BY month",
                orgId));
    }

    @GetMapping("/ptp-kept-rate")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> ptpKeptRate(
            @RequestParam UUID organizationId, @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = resolveOrgId(principal, organizationId, reason, "kpi:ptpKeptRate");
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_ptp_kept_rate WHERE organization_id = ? ORDER BY bucket_month",
                orgId));
    }

    @GetMapping("/contactability")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> contactability(
            @RequestParam UUID organizationId, @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = resolveOrgId(principal, organizationId, reason, "kpi:contactability");
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_contactability WHERE organization_id = ? ORDER BY bucket_month",
                orgId));
    }

    @GetMapping("/complaint-rate")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> complaintRate(
            @RequestParam UUID organizationId) {
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_complaint_rate WHERE organization_id = ? "
                        + "ORDER BY month",
                organizationId));
    }

    @GetMapping("/grievance-mttr")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> grievanceMttr(
            @RequestParam UUID organizationId) {
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_grievance_mttr WHERE organization_id = ?",
                organizationId));
    }

    @GetMapping("/reconciliation-gap")
    @PreAuthorize(ADMINS)
    public ApiResponse<List<Map<String, Object>>> reconciliationGap(
            @RequestParam UUID organizationId, @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = resolveOrgId(principal, organizationId, reason, "kpi:reconciliationGap");
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_reconciliation_gap WHERE organization_id = ? ORDER BY bucket_month",
                orgId));
    }

    @GetMapping("/calling-hours-violations")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> callingHoursViolations(
            @RequestParam UUID organizationId, @RequestParam String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Platform-admin-only, so this is always cross-org -- reason is required, not optional.
        platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), organizationId, reason,
                "kpi:callingHoursViolations");
        return ApiResponse.success(jdbc.queryForList(
                "SELECT * FROM v_kpi_calling_hours_violations WHERE organization_id = ? "
                        + "ORDER BY day DESC",
                organizationId));
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /**
     * organizationId is caller-supplied on every one of these endpoints (there's no case-level
     * resource to derive it from first, unlike most other controllers' beginUnattendedCrossOrgAccess
     * usages) -- so the target org is always known up front, and a platform admin's access always
     * goes through beginCrossOrgAccess (reason required) rather than the unattended variant.
     */
    private UUID resolveOrgId(UserPrincipal principal, UUID requestedOrgId, String reason, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), requestedOrgId, reason, resource);
            return requestedOrgId;
        }
        UUID callerOrgId = principal.getOrganizationId();
        if (callerOrgId == null) {
            throw new BusinessException("Caller has no organization context");
        }
        if (!callerOrgId.equals(requestedOrgId)) {
            throw new ResourceNotFoundException("Organization not found");
        }
        return requestedOrgId;
    }
}
