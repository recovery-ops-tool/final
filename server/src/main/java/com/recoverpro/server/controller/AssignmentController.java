package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.BulkAssignRequest;
import com.recoverpro.server.dto.request.ReassignRequest;
import com.recoverpro.server.dto.response.*;
import com.recoverpro.server.enums.AssignmentStatus;
import com.recoverpro.server.enums.Priority;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AssignmentService;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.IdempotencyKeyService;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.repository.AllocationRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assignments")
@RequiredArgsConstructor
@Slf4j
public class AssignmentController {

    private static final String READERS = Authz.ALL_STAFF;
    private static final String LEADS = Authz.LEADS;
    private static final String ADMINS = Authz.ADMINS;

    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "assignmentDate", "assignmentDate",
            "createdAt", "createdAt",
            "status", "status",
            "priority", "priority");

    private final AssignmentService assignmentService;
    private final AllocationService allocationService;
    private final AllocationRepository allocationRepository;
    private final IdempotencyKeyService idempotencyKeyService;

    @PostMapping("/bulk")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<BulkAssignResponse>> bulkAssign(
            @Valid @RequestBody BulkAssignRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (idempotencyKey != null) {
            IdempotencyKeyService.IdempotencyResult idemResult =
                    idempotencyKeyService.tryClaim("assignment:bulk", idempotencyKey, request.getAgentId());
            if (idemResult == IdempotencyKeyService.IdempotencyResult.REPLAY) {
                log.info("Idempotent replay for POST /assignments/bulk key={}", idempotencyKey);
                return ResponseEntity.ok(ApiResponse.of("Bulk assignment already processed", null));
            }
            if (idemResult == IdempotencyKeyService.IdempotencyResult.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.of("Idempotency key already used for a different agent", null));
            }
        }

        log.info("POST /bulk - agentId={}, date={}", request.getAgentId(), request.getAssignmentDate());
        BulkAssignResponse response = assignmentService.bulkAssign(request, principal.getId());
        HttpStatus status = response.isRolledBack() ? HttpStatus.CONFLICT : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of("Bulk assignment processed", response));
    }

    @PatchMapping("/{id}/reassign")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<AssignmentResponse>> reassign(
            @PathVariable UUID id,
            @Valid @RequestBody ReassignRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssignmentResponse current = assignmentService.getAssignmentById(id);
        assertSameTenant(current.getOrganizationId(), principal);
        AssignmentResponse response = assignmentService.reassign(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Assignment reassigned successfully", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<AssignmentResponse>> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssignmentResponse resp = assignmentService.getAssignmentById(id);
        assertSameTenant(resp.getOrganizationId(), principal);
        if (isOnlyFieldOfficer(principal) && !principal.getId().equals(resp.getAgentId())) {
            throw new ResourceNotFoundException("Assignment not found");
        }
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<AssignmentResponse>>> getAssignments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) AssignmentStatus status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "assignmentDate") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        UUID effectiveOrg = resolveOrgId(principal, orgId);
        if (effectiveOrg == null) throw new BusinessException("No organization context for caller");

        UUID effectiveAgent = isOnlyFieldOfficer(principal) ? principal.getId() : agentId;
        Sort sort = SafeSort.from(sortBy, sortDir, SORTABLE_FIELDS, "assignmentDate");
        Page<AssignmentResponse> result = assignmentService.getAssignments(
                effectiveOrg, effectiveAgent, date, status, priority, PageRequest.of(page, size, sort));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/agent/{agentId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<AssignmentResponse>>> getByAgentAndDate(
            @PathVariable UUID agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (isOnlyFieldOfficer(principal) && !principal.getId().equals(agentId)) {
            throw new ResourceNotFoundException("Assignments not found");
        }
        Page<AssignmentResponse> result = assignmentService.getAssignmentsByAgentAndDate(
                agentId, date, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("sequenceOrder").ascending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/summary/agent/{agentId}")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<AgentAssignmentSummary>> getAgentSummary(
            @PathVariable UUID agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID effectiveOrg = resolveOrgId(principal, orgId);
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.getAgentSummary(agentId, date, effectiveOrg)));
    }

    @GetMapping("/summary/date")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<DateAssignmentSummary>> getDateSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID effectiveOrg = resolveOrgId(principal, orgId);
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.getDateSummary(date, effectiveOrg)));
    }

    @GetMapping("/capacity/agent/{agentId}")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<Integer>> getRemainingCapacity(
            @PathVariable UUID agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID effectiveOrg = resolveOrgId(principal, orgId);
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.getRemainingCapacity(agentId, date, effectiveOrg)));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<AssignmentResponse>>> getByAllocation(
            @PathVariable UUID allocationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        AllocationResponse alloc = allocationService.getAllocationById(allocationId);
        assertSameTenant(alloc.getOrganizationId(), principal);
        List<UUID> allocationIds = (alloc.getLoanNumber() != null && !alloc.getLoanNumber().isBlank())
                ? allocationRepository.findIdsByOrganizationIdAndLoanNumber(
                        alloc.getOrganizationId(), alloc.getLoanNumber())
                : List.of(allocationId);
        return ResponseEntity.ok(ApiResponse.success(assignmentService.getByAllocationIds(allocationIds)));
    }

    @GetMapping("/my-cases")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PagedResponse<MyAssignedCaseResponse>>> getMyActiveCases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        PagedResponse<MyAssignedCaseResponse> result = assignmentService.getMyActiveCases(
                principal.getId(), principal.getOrganizationId(), PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<Void>> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssignmentResponse current = assignmentService.getAssignmentById(id);
        assertSameTenant(current.getOrganizationId(), principal);
        assignmentService.cancelAssignment(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Assignment cancelled", null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        AssignmentResponse current = assignmentService.getAssignmentById(id);
        assertSameTenant(current.getOrganizationId(), principal);
        assignmentService.softDeleteAssignment(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Assignment deleted", null));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private boolean isOnlyFieldOfficer(UserPrincipal p) {
        boolean hasFo = p.getAuthorities().stream().anyMatch(a -> "ROLE_FO".equals(a.getAuthority()));
        boolean hasLead = p.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().matches("ROLE_(TL|MANAGER|ORG_ADMIN|PLATFORM_ADMIN)"));
        return hasFo && !hasLead;
    }

    private UUID resolveOrgId(UserPrincipal p, UUID requested) {
        if (isPlatformAdmin(p)) return requested != null ? requested : p.getOrganizationId();
        return p.getOrganizationId();
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;
        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Assignment not found");
        }
    }
}
