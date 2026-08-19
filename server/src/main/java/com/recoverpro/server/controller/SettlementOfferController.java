package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.CreateSettlementOfferRequest;
import com.recoverpro.server.dto.request.SettlementBorrowerAcceptRequest;
import com.recoverpro.server.dto.request.SettlementMarkPaidRequest;
import com.recoverpro.server.dto.request.SettlementRejectRequest;
import com.recoverpro.server.dto.response.SettlementOfferResponse;
import com.recoverpro.server.enums.SettlementOfferStatus;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.SettlementOfferService;
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
@RequestMapping("/api/v1/settlement-offers")
@RequiredArgsConstructor
public class SettlementOfferController {

    private static final String READERS = Authz.ALL_STAFF;
    private static final String SUBMITTERS = Authz.ALL_STAFF_NO_TRACER;
    private static final String APPROVERS = Authz.LEADS;

    private final SettlementOfferService settlementOfferService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PostMapping
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> draft(
            @Valid @RequestBody CreateSettlementOfferRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        SettlementOfferResponse response = settlementOfferService.draft(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Settlement offer drafted", response));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> approve(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:approve:" + id);
        assertSameTenant(id, principal);
        boolean orgLevel = isPlatformAdmin(principal) || hasRole(principal, "ROLE_ORG_ADMIN");
        return ResponseEntity.ok(ApiResponse.of("Settlement offer approved",
                settlementOfferService.approve(id, principal.getId(), orgLevel)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> reject(
            @PathVariable UUID id,
            @Valid @RequestBody SettlementRejectRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:reject:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Settlement offer rejected",
                settlementOfferService.reject(id, request, principal.getId())));
    }

    @PostMapping("/{id}/propose")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> propose(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:propose:" + id);
        assertSameTenant(id, principal);
        return ResponseEntity.ok(ApiResponse.of("Settlement offer proposed to borrower",
                settlementOfferService.propose(id, principal.getId())));
    }

    @PostMapping("/{id}/borrower-accept")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> borrowerAccept(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) SettlementBorrowerAcceptRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:borrowerAccept:" + id);
        assertSameTenant(id, principal);
        SettlementBorrowerAcceptRequest body = request != null ? request : new SettlementBorrowerAcceptRequest();
        return ResponseEntity.ok(ApiResponse.of("Accepted by borrower",
                settlementOfferService.borrowerAccept(id, body, principal.getId())));
    }

    @PostMapping("/{id}/mark-paid")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> markPaid(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) SettlementMarkPaidRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:markPaid:" + id);
        assertSameTenant(id, principal);
        SettlementMarkPaidRequest body = request != null ? request : new SettlementMarkPaidRequest();
        return ResponseEntity.ok(ApiResponse.of("Settlement offer marked paid",
                settlementOfferService.markPaid(id, body, principal.getId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<SettlementOfferResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:getById:" + id);
        SettlementOfferResponse response = settlementOfferService.getById(id);
        assertSameTenantOrg(response.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<SettlementOfferResponse>>> getByAllocation(
            @PathVariable UUID allocationId, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "settlementOffers:byAllocation:" + allocationId);
        return ResponseEntity.ok(ApiResponse.success(
                settlementOfferService.getByAllocationId(allocationId)));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<SettlementOfferResponse>>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) SettlementOfferStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID targetOrgId = resolveListOrgId(principal, orgId, reason);
        Page<SettlementOfferResponse> result = settlementOfferService.getByOrganization(
                targetOrgId, status, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    private void assertSameTenant(UUID offerId, UserPrincipal principal) {
        SettlementOfferResponse current = settlementOfferService.getById(offerId);
        assertSameTenantOrg(current.getOrganizationId(), principal);
    }

    private void assertSameTenantOrg(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;

        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Settlement offer not found");
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
                        principal.getId(), target, reason, "settlementOffers:list");
            }
            return target;
        }
        return principal.getOrganizationId();
    }
}
