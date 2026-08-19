package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.CreateRestructureProposalRequest;
import com.recoverpro.server.dto.request.RestructureBorrowerAcceptRequest;
import com.recoverpro.server.dto.request.RestructureRejectRequest;
import com.recoverpro.server.dto.response.RestructureProposalResponse;
import com.recoverpro.server.enums.RestructureStatus;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.RestructureProposalService;
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
@RequestMapping("/api/v1/restructure-proposals")
@RequiredArgsConstructor
public class RestructureProposalController {

    private static final String READERS = Authz.ALL_STAFF;
    private static final String LEADS = Authz.LEADS;
    private static final String LENDER = Authz.ADMINS;

    private final RestructureProposalService restructureProposalService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PostMapping
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<RestructureProposalResponse>> draft(
            @Valid @RequestBody CreateRestructureProposalRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        RestructureProposalResponse response = restructureProposalService.draft(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Restructure proposal drafted", response));
    }

    @PostMapping("/{id}/propose-to-lender")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<RestructureProposalResponse>> proposeToLender(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "restructureProposals:proposeToLender:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Sent to lender",
                restructureProposalService.proposeToLender(id, principal.getId())));
    }

    @PostMapping("/{id}/lender-approve")
    @PreAuthorize(LENDER)
    public ResponseEntity<ApiResponse<RestructureProposalResponse>> lenderApprove(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "restructureProposals:lenderApprove:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Approved by lender",
                restructureProposalService.lenderApprove(id, principal.getId())));
    }

    @PostMapping("/{id}/lender-reject")
    @PreAuthorize(LENDER)
    public ResponseEntity<ApiResponse<RestructureProposalResponse>> lenderReject(
            @PathVariable UUID id,
            @Valid @RequestBody RestructureRejectRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "restructureProposals:lenderReject:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Rejected by lender",
                restructureProposalService.lenderReject(id, request, principal.getId())));
    }

    @PostMapping("/{id}/borrower-accept")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<RestructureProposalResponse>> borrowerAccept(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RestructureBorrowerAcceptRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "restructureProposals:borrowerAccept:" + id);
        assertSameTenant(id, principal);
        RestructureBorrowerAcceptRequest body =
                request != null ? request : new RestructureBorrowerAcceptRequest();
        return ResponseEntity.ok(ApiResponse.of("Accepted by borrower",
                restructureProposalService.borrowerAccept(id, body)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<RestructureProposalResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "restructureProposals:getById:" + id);
        RestructureProposalResponse response = restructureProposalService.getById(id);
        assertSameTenantOrg(response.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<RestructureProposalResponse>>> getByAllocation(
            @PathVariable UUID allocationId, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "restructureProposals:byAllocation:" + allocationId);
        return ResponseEntity.ok(ApiResponse.success(
                restructureProposalService.getByAllocationId(allocationId)));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<RestructureProposalResponse>>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) RestructureStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID targetOrgId = resolveListOrgId(principal, orgId, reason);
        Page<RestructureProposalResponse> result = restructureProposalService.getByOrganization(
                targetOrgId, status, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    private void assertSameTenant(UUID proposalId, UserPrincipal principal) {
        RestructureProposalResponse current = restructureProposalService.getById(proposalId);
        assertSameTenantOrg(current.getOrganizationId(), principal);
    }

    private void assertSameTenantOrg(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;

        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Restructure proposal not found");
        }
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /**
     * restructure_proposals' RLS policy went fail-closed in V057 with no platform-admin bypass, so
     * every by-id fetch (target org unknown until after the fetch, hence the unattended escape hatch
     * rather than a reason prompt -- same as PtpController's getById/getPtpHistory precedent) returned
     * empty for a platform admin regardless of this controller's own "if platform admin, skip the org
     * check" logic in assertSameTenantOrg.
     */
    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }

    /**
     * list() previously had no way for a platform admin to target any org at all -- it always queried
     * principal.getOrganizationId(), which is NULL for a platform admin, so the endpoint was silently
     * broken for them regardless of RLS. The target org is known up front here (an explicit orgId), so
     * this uses the reason-requiring beginCrossOrgAccess rather than the unattended escape hatch.
     */
    private UUID resolveListOrgId(UserPrincipal principal, UUID requestedOrgId, String reason) {
        if (isPlatformAdmin(principal)) {
            UUID target = requestedOrgId != null ? requestedOrgId : principal.getOrganizationId();
            if (target != null) {
                platformAdminAccessGuard.beginCrossOrgAccess(
                        principal.getId(), target, reason, "restructureProposals:list");
            }
            return target;
        }
        return principal.getOrganizationId();
    }
}
