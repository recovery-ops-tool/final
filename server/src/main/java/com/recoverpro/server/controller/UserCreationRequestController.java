package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.request.CreateUserRequestDto;
import com.recoverpro.server.dto.request.ReviewRequestDto;
import com.recoverpro.server.dto.response.UserCreationRequestResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.UserCreationRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/user-requests")
@RequiredArgsConstructor
public class UserCreationRequestController {

    private final UserCreationRequestService service;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    private static final String ADMINS = Authz.ADMINS;

    @PostMapping
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<UserCreationRequestResponse>> submit(
            @Valid @RequestBody CreateUserRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.submit(dto, principal), "Request submitted for approval"));
    }

    @GetMapping("/pending")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<PagedResponse<UserCreationRequestResponse>>> listPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "userRequests:pending");
        Page<UserCreationRequestResponse> result = service.listPendingForApprover(
                principal, PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/mine")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<PagedResponse<UserCreationRequestResponse>>> listMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        Page<UserCreationRequestResponse> result = service.listMyRequests(
                principal, PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/pending-count")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<Long>> pendingCount(@AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "userRequests:pendingCount");
        return ResponseEntity.ok(ApiResponse.success(service.countPendingForApprover(principal)));
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<UserCreationRequestResponse>> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "userRequests:review:" + id);
        return ResponseEntity.ok(ApiResponse.success(service.review(id, dto, principal)));
    }

    /**
     * user_creation_requests' RLS policy (V058) is fail-closed with no platform-admin bypass -- the
     * whole point of listPending/pendingCount/review's platform-admin branch is to surface and let
     * them approve ORG_ADMIN-role requests (the tier above what an ORG_ADMIN can approve), but that
     * branch's requestRepo query was silently RLS-filtered to nothing before this fix, meaning
     * platform admins could never actually see or approve a single new-org-admin request through this
     * flow -- the entire top tier of the approval chain was unreachable.
     */
    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        boolean isPlatformAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        if (isPlatformAdmin) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }
}
