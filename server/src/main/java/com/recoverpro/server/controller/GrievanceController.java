package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.AcknowledgeGrievanceRequest;
import com.recoverpro.server.dto.request.EscalateGrievanceRequest;
import com.recoverpro.server.dto.request.InvestigateGrievanceRequest;
import com.recoverpro.server.dto.request.RaiseGrievanceRequest;
import com.recoverpro.server.dto.request.ResolveGrievanceRequest;
import com.recoverpro.server.dto.response.GrievanceResponse;
import com.recoverpro.server.enums.GrievanceStatus;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.GrievanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/grievances")
@RequiredArgsConstructor
public class GrievanceController {

    private static final String READERS = Authz.ALL_STAFF;
    private static final String RAISERS =
            "hasAnyRole('FO','CALLER','TL','MANAGER')";
    private static final String HANDLERS = Authz.LEADS;

    private final GrievanceService grievanceService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PostMapping
    @PreAuthorize(RAISERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> raise(
            @Valid @RequestBody RaiseGrievanceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        GrievanceResponse response = grievanceService.raise(request, principal.getOrganizationId(), principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Grievance raised", response));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(HANDLERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> acknowledge(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) AcknowledgeGrievanceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:acknowledge:" + id);
        assertSameTenant(id, principal);
        AcknowledgeGrievanceRequest body = request != null ? request : new AcknowledgeGrievanceRequest();
        return ResponseEntity.ok(ApiResponse.of("Grievance acknowledged",
                grievanceService.acknowledge(id, body, principal.getId())));
    }

    @PostMapping("/{id}/investigate")
    @PreAuthorize(HANDLERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> investigate(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) InvestigateGrievanceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:investigate:" + id);
        assertSameTenant(id, principal);
        InvestigateGrievanceRequest body = request != null ? request : new InvestigateGrievanceRequest();
        return ResponseEntity.ok(ApiResponse.of("Grievance investigation started",
                grievanceService.investigate(id, body, principal.getId())));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize(HANDLERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> escalate(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) EscalateGrievanceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:escalate:" + id);
        assertSameTenant(id, principal);
        EscalateGrievanceRequest body = request != null ? request : new EscalateGrievanceRequest();
        return ResponseEntity.ok(ApiResponse.of("Grievance escalated",
                grievanceService.escalate(id, body, principal.getId())));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize(HANDLERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> resolve(
            @PathVariable UUID id,
            @Valid @RequestBody ResolveGrievanceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:resolve:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Grievance resolved",
                grievanceService.resolve(id, request, principal.getId())));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(HANDLERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> close(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:close:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Grievance closed",
                grievanceService.close(id, principal.getId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<GrievanceResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:getById:" + id);
        GrievanceResponse response = grievanceService.getById(id);
        assertSameTenantOrg(response.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<GrievanceResponse>>> getByAllocation(
            @PathVariable UUID allocationId, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "grievances:byAllocation:" + allocationId);
        return ResponseEntity.ok(ApiResponse.success(
                grievanceService.getByAllocationId(allocationId)));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<GrievanceResponse>>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) GrievanceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID targetOrgId = resolveListOrgId(principal, orgId, reason);
        Page<GrievanceResponse> result = grievanceService.getByOrganization(
                targetOrgId, status, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    private void assertSameTenant(UUID grievanceId, UserPrincipal principal) {
        GrievanceResponse current = grievanceService.getById(grievanceId);
        assertSameTenantOrg(current.getOrganizationId(), principal);
    }

    private void assertSameTenantOrg(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;

        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Grievance not found");
        }
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return hasRole(principal, "ROLE_PLATFORM_ADMIN");
    }

    private static boolean hasRole(UserPrincipal principal, String role) {
        return principal.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }

    private UUID resolveListOrgId(UserPrincipal principal, UUID requestedOrgId, String reason) {
        if (isPlatformAdmin(principal)) {
            UUID target = requestedOrgId != null ? requestedOrgId : principal.getOrganizationId();
            if (target != null) {
                platformAdminAccessGuard.beginCrossOrgAccess(
                        principal.getId(), target, reason, "grievances:list");
            }
            return target;
        }
        return principal.getOrganizationId();
    }
}
