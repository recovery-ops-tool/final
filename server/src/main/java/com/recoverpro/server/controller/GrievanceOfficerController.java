package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.request.UpsertGrievanceOfficerRequest;
import com.recoverpro.server.dto.response.GrievanceOfficerResponse;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.GrievanceOfficerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/grievance-officers")
@RequiredArgsConstructor
public class GrievanceOfficerController {

    private static final String READERS = Authz.ALL_STAFF;
    private static final String WRITERS = Authz.ADMINS;

    private final GrievanceOfficerService grievanceOfficerService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PutMapping
    @PreAuthorize(WRITERS)
    public ResponseEntity<ApiResponse<GrievanceOfficerResponse>> upsert(
            @Valid @RequestBody UpsertGrievanceOfficerRequest request,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID targetOrgId = resolveOrgId(principal, orgId, reason, "grievanceOfficers:upsert");
        return ResponseEntity.ok(ApiResponse.of("Grievance officer saved",
                grievanceOfficerService.upsert(targetOrgId, request, principal.getId())));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<GrievanceOfficerResponse>> get(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID targetOrgId = resolveOrgId(principal, orgId, reason, "grievanceOfficers:get");
        return ResponseEntity.ok(ApiResponse.success(
                grievanceOfficerService.getByOrganization(targetOrgId)));
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private UUID resolveOrgId(UserPrincipal principal, UUID requestedOrgId, String reason, String resource) {
        if (isPlatformAdmin(principal)) {
            UUID target = requestedOrgId != null ? requestedOrgId : principal.getOrganizationId();
            if (target != null) {
                platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), target, reason, resource);
            }
            return target;
        }
        return principal.getOrganizationId();
    }
}
