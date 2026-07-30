package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.CreateNonContactableRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.NonContactableResponse;
import com.recoverpro.server.entity.NonContactable;
import com.recoverpro.server.repository.NonContactableRepository;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.NonContactableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Non-contactable visit outcome (spec §6.4).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/non-contactables")
@RequiredArgsConstructor
public class NonContactableController {

    private static final String SUBMITTERS =
            "hasAnyRole('FO','FO')";

    private static final String READERS =
            "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','MANAGER','TL','FO','CALLER','TRACER','ORG_ADMIN')";

    private static final String ADMINS =
            "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')";

    private final NonContactableRepository repo;
    private final AllocationService allocationService;
    private final NonContactableService nonContactableService;

    @PostMapping
    @PreAuthorize(SUBMITTERS)
    @Transactional
    public ResponseEntity<ApiResponse<NonContactableResponse>> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateNonContactableRequest request) {

        NonContactableResponse response = nonContactableService.create(
                request, principal.getId(), principal.getOrganizationId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Non-contactable recorded", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<NonContactableResponse>> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        NonContactable nc = repo.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Non-contactable not found"));

        assertSameTenant(nc.getOrganizationId(), principal);

        if (isOnlyFieldOfficer(principal) &&
                !principal.getId().equals(nc.getAgentId())) {
            throw new ResourceNotFoundException("Non-contactable not found");
        }

        return ResponseEntity.ok(ApiResponse.success(toResponse(nc)));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<NonContactableResponse>>> listForAllocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID allocationId) {

        AllocationResponse alloc =
                allocationService.getAllocationById(allocationId);

        assertSameTenant(alloc.getOrganizationId(), principal);

        List<NonContactable> rows =
                repo.findByAllocationIdOrderByCreatedAtDesc(allocationId);

        if (isOnlyFieldOfficer(principal)) {
            rows = rows.stream()
                    .filter(r -> principal.getId().equals(r.getAgentId()))
                    .toList();
        }

        return ResponseEntity.ok(
                ApiResponse.success(rows.stream().map(this::toResponse).toList())
        );
    }

    @GetMapping
    @PreAuthorize(READERS)
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<PagedResponse<NonContactableResponse>>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID orgId = principal.getOrganizationId();
        if (orgId == null) {
            throw new BusinessException("Caller has no organization context");
        }

        Pageable pageable = PageRequest.of(page, size);

        Page<NonContactable> result =
                isOnlyFieldOfficer(principal)
                        ? repo.findByOrganizationIdAndAgentIdOrderByCreatedAtDesc(
                        orgId, principal.getId(), pageable)
                        : repo.findByOrganizationIdOrderByCreatedAtDesc(
                        orgId, pageable);

        return ResponseEntity.ok(
                ApiResponse.success(
                        PagedResponse.from(result.map(this::toResponse))
                )
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMINS)
    @Transactional
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        NonContactable nc = repo.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Non-contactable not found"));

        assertSameTenant(nc.getOrganizationId(), principal);

        repo.delete(nc);

        return ResponseEntity.ok(
                ApiResponse.of("Non-contactable deleted", null)
        );
    }

    // ─── Helpers ───────────────────────────────────────────────

    private NonContactableResponse toResponse(NonContactable nc) {
        return NonContactableResponse.builder()
                .id(nc.getId())
                .organizationId(nc.getOrganizationId())
                .allocationId(nc.getAllocationId())
                .visitId(nc.getVisitId())
                .agentId(nc.getAgentId())
                .reason(nc.getReason())
                .notes(nc.getNotes())
                .createdAt(nc.getCreatedAt())
                .build();
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private boolean isOnlyFieldOfficer(UserPrincipal p) {
        boolean hasFo = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_FO".equals(a.getAuthority())
                        || "ROLE_FO".equals(a.getAuthority()));

        boolean hasLead = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TL".equals(a.getAuthority())
                        || "ROLE_MANAGER".equals(a.getAuthority())
                        || "ROLE_ORG_ADMIN".equals(a.getAuthority())
                        || "ROLE_PLATFORM_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORG_ADMIN".equals(a.getAuthority())
                        || "ROLE_ORG_ADMIN".equals(a.getAuthority()));

        return hasFo && !hasLead;
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;

        if (resourceOrg == null ||
                !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Non-contactable not found");
        }
    }
}