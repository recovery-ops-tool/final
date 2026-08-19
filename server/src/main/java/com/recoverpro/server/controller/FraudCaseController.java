package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.request.CreateFraudCaseRequest;
import com.recoverpro.server.dto.request.TransitionFraudCaseRequest;
import com.recoverpro.server.dto.response.FraudCaseResponse;
import com.recoverpro.server.enums.FraudCaseStatus;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.FraudCaseService;
import com.recoverpro.server.service.UserActionAuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Fraud case endpoints (design-doc §9.10).
 *
 *   POST   /api/v1/fraud-cases                 file a new case
 *   PATCH  /api/v1/fraud-cases/{id}/transition state machine moves
 *   POST   /api/v1/fraud-cases/{id}/cfr-lookup CFR portal lookup (stub)
 *   GET    /api/v1/fraud-cases/{id}            view one
 *   GET    /api/v1/fraud-cases                 list (filter by status)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-cases")
@RequiredArgsConstructor
public class FraudCaseController {

    private final FraudCaseService fraudCaseService;
    private final UserActionAuditService auditLogService;

    private static final String ADMINS = Authz.ADMINS;

    @PostMapping
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<FraudCaseResponse>> create(
            @Valid @RequestBody CreateFraudCaseRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Fraud case reported",
                        fraudCaseService.create(request, principal.getId())));
    }

    @PatchMapping("/{id}/transition")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<FraudCaseResponse>> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionFraudCaseRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                fraudCaseService.transition(id, request, principal.getId())));
    }

    @PostMapping("/{id}/cfr-lookup")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<FraudCaseResponse>> cfrLookup(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/fraud-cases/{}/cfr-lookup", id);
        return ResponseEntity.ok(ApiResponse.success(
                fraudCaseService.runCfrLookup(id, principal.getId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<FraudCaseResponse>> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        FraudCaseResponse fraudCase = fraudCaseService.getById(id);
        auditLogService.logUserAction(principal.getId(), "FRAUD_CASE_READ",
                "case=" + fraudCase.getCaseNumber() + " org=" + fraudCase.getOrganizationId());
        return ResponseEntity.ok(ApiResponse.success(fraudCase));
    }

    @GetMapping
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<PagedResponse<FraudCaseResponse>>> list(
            @RequestParam UUID orgId,
            @RequestParam(required = false) FraudCaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FraudCaseResponse> result = fraudCaseService.list(
                orgId, status, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("reportedAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }
}