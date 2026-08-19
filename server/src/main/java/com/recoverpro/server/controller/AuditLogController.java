package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.AssignmentResponse;
import com.recoverpro.server.dto.response.AuditLogResponse;
import com.recoverpro.server.dto.response.UserActionAuditResponse;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.AssignmentService;
import com.recoverpro.server.service.AuditLogService;
import com.recoverpro.server.service.UserActionAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private static final String AUDIT_READERS = Authz.MANAGERS_AND_ABOVE;

    private final AuditLogService auditLogService;
    private final UserActionAuditService userActionAuditService;
    private final AssignmentService assignmentService;
    private final AllocationService allocationService;
    private final AllocationRepository allocationRepository;
    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize(AUDIT_READERS)
    public ResponseEntity<ApiResponse<PagedResponse<AuditLogResponse>>> getByOrganization(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (isPlatformAdmin(principal)) {
            return ResponseEntity.ok(ApiResponse.success(
                    PagedResponse.<AuditLogResponse>builder()
                            .content(List.of()).page(0).size(size)
                            .totalElements(0).totalPages(0).last(true).build()));
        }

        Page<AuditLogResponse> logs = auditLogService.getLogsByOrganization(
                principal.getOrganizationId(),
                PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(logs)));
    }

    @GetMapping("/assignment/{assignmentId}")
    @PreAuthorize(AUDIT_READERS)
    public ResponseEntity<ApiResponse<PagedResponse<AuditLogResponse>>> getByAssignment(
            @PathVariable UUID assignmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        AssignmentResponse assignment = assignmentService.getAssignmentById(assignmentId);
        assertSameTenant(assignment.getOrganizationId(), principal);
        Page<AuditLogResponse> logs = auditLogService.getLogsByAssignment(
                assignmentId, PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(logs)));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(AUDIT_READERS)
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>> getByAllocation(
            @PathVariable UUID allocationId,
            @AuthenticationPrincipal UserPrincipal principal) {

        AllocationResponse allocation = allocationService.getAllocationById(allocationId);
        assertSameTenant(allocation.getOrganizationId(), principal);
        List<UUID> allocationIds = (allocation.getLoanNumber() != null && !allocation.getLoanNumber().isBlank())
                ? allocationRepository.findIdsByOrganizationIdAndLoanNumber(
                        allocation.getOrganizationId(), allocation.getLoanNumber())
                : List.of(allocationId);
        return ResponseEntity.ok(ApiResponse.success(auditLogService.getLogsByAllocationIds(allocationIds)));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize(AUDIT_READERS)
    public ResponseEntity<ApiResponse<PagedResponse<AuditLogResponse>>> getByUser(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertUserInTenant(userId, principal);
        Page<AuditLogResponse> logs = auditLogService.getLogsByPerformedBy(
                userId, PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(logs)));
    }

    @GetMapping("/actions/user/{userId}")
    @PreAuthorize(AUDIT_READERS)
    public ResponseEntity<ApiResponse<PagedResponse<UserActionAuditResponse>>> getUserActionLogs(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertUserInTenant(userId, principal);
        Page<UserActionAuditResponse> logs = userActionAuditService.getUserActionLogs(
                userId, PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(logs)));
    }

    @GetMapping("/actions")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PagedResponse<UserActionAuditResponse>>> getAllUserActionLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<UserActionAuditResponse> logs = userActionAuditService.getAllUserActionLogs(
                PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(logs)));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;
        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId()))
            throw new ResourceNotFoundException("Audit logs not found");
    }

    private void assertUserInTenant(UUID userId, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;
        UUID targetOrg = userRepository.findById(userId)
                .map(u -> u.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Audit logs not found"));
        if (!targetOrg.equals(principal.getOrganizationId()))
            throw new ResourceNotFoundException("Audit logs not found");
    }
}
