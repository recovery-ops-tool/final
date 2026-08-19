package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.AllocationFilterRequest;
import com.recoverpro.server.dto.request.BulkAssignToFoRequest;
import com.recoverpro.server.dto.request.LinkBorrowerRequest;
import com.recoverpro.server.dto.request.UpdateAllocationDispositionRequest;
import com.recoverpro.server.dto.request.UpdateAllocationStatusRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.enums.AllocationStatus;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.IdempotencyKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/allocations")
@RequiredArgsConstructor
public class AllocationController {

    private static final String READERS = Authz.ALL_STAFF;
    private static final String LEADS = Authz.LEADS;
    private static final String ADMINS = Authz.ADMINS;

    private final AllocationService allocationService;
    private final IdempotencyKeyService idempotencyKeyService;

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<AllocationResponse>>> getAllocations(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) AllocationStatus status,
            @RequestParam(required = false) UUID fileUploadId,
            @RequestParam(required = false) UUID assignedToUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection) {

        UUID effectiveOrgId = resolveOrgId(principal, organizationId);
        if (effectiveOrgId == null) {
            throw new BusinessException("No organization context available for this caller");
        }

        AllocationFilterRequest filter = AllocationFilterRequest.builder()
                .organizationId(effectiveOrgId)
                .searchTerm(searchTerm)
                .status(status)
                .fileUploadId(fileUploadId)
                .assignedToUserId(assignedToUserId)
                .page(page).size(size)
                .sortBy(sortBy).sortDirection(sortDirection)
                .build();

        return ResponseEntity.ok(ApiResponse.success(allocationService.getAllocations(filter)));
    }

    @PostMapping("/bulk-assign")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<List<AllocationResponse>>> bulkAssignToFo(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BulkAssignToFoRequest request) {

        UUID callerOrg = resolveOrgId(principal, null);
        if (callerOrg == null) throw new BusinessException("Caller has no organization context");
        List<AllocationResponse> assigned = allocationService.bulkAssignToFo(callerOrg, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Assigned " + assigned.size() + " case(s) to FO", assigned));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<AllocationResponse>> getAllocationById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        AllocationResponse resp = allocationService.getAllocationById(id);
        assertSameTenant(resp.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<AllocationResponse>> updateAllocationStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UpdateAllocationStatusRequest request) {

        AllocationResponse current = allocationService.getAllocationById(id);
        assertSameTenant(current.getOrganizationId(), principal);

        if (idempotencyKey != null) {
            IdempotencyKeyService.IdempotencyResult result =
                    idempotencyKeyService.tryClaim("allocation:status", idempotencyKey, id);
            if (result == IdempotencyKeyService.IdempotencyResult.REPLAY) {
                return ResponseEntity.ok(ApiResponse.of("Allocation status updated successfully", current));
            }
            if (result == IdempotencyKeyService.IdempotencyResult.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.of("Idempotency key already used for a different allocation", null));
            }
        }

        AllocationResponse response = allocationService.updateAllocationStatus(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Allocation status updated successfully", response));
    }

    @PatchMapping("/{id}/disposition")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<AllocationResponse>> updateAllocationDisposition(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAllocationDispositionRequest request) {

        AllocationResponse current = allocationService.getAllocationById(id);
        assertSameTenant(current.getOrganizationId(), principal);

        AllocationResponse response = allocationService.updateAllocationDisposition(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Disposition updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<Void>> deleteAllocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        AllocationResponse current = allocationService.getAllocationById(id);
        assertSameTenant(current.getOrganizationId(), principal);
        allocationService.softDeleteAllocation(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Allocation deleted successfully", null));
    }

    @GetMapping("/borrower/{borrowerId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<AllocationResponse>>> listForBorrower(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID borrowerId) {
        List<AllocationResponse> all = allocationService.getAllocationsForBorrower(borrowerId);
        if (!isPlatformAdmin(principal)) {
            UUID callerOrg = principal.getOrganizationId();
            all = all.stream()
                    .filter(a -> a.getOrganizationId() != null && a.getOrganizationId().equals(callerOrg))
                    .toList();
        }
        return ResponseEntity.ok(ApiResponse.success(all));
    }

    @PatchMapping("/{id}/borrower")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<AllocationResponse>> linkBorrower(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody LinkBorrowerRequest body) {
        AllocationResponse current = allocationService.getAllocationById(id);
        assertSameTenant(current.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(
                allocationService.linkBorrower(id, body.getBorrowerId(), principal.getId())));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private UUID resolveOrgId(UserPrincipal p, UUID requested) {
        if (isPlatformAdmin(p)) return requested != null ? requested : p.getOrganizationId();
        return p.getOrganizationId();
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;
        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Allocation not found");
        }
    }
}
