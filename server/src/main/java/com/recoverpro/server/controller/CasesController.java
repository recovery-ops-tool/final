package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.CaseTimelineResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.CaseTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
public class CasesController {

    private static final String READERS = Authz.ALL_STAFF;

    private final CaseTimelineService caseTimelineService;
    private final AllocationService allocationService;

    @GetMapping("/{allocationId}/timeline")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<CaseTimelineResponse>> getTimeline(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID allocationId) {

        AllocationResponse allocation = allocationService.getAllocationById(allocationId);
        assertSameTenant(allocation.getOrganizationId(), principal);

        return ResponseEntity.ok(ApiResponse.success(caseTimelineService.getTimeline(allocationId)));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;
        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Case not found");
        }
    }
}
