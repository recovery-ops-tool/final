package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.CreateDailyDispatchRequest;
import com.recoverpro.server.dto.request.RemoveDispatchCaseRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.DailyDispatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Daily dispatch (spec §6.1). TL curates a per-FO list of cases for a date;
 * FO reads their own list to start the day's visits.
 *
 * Tenant id is derived from the JWT here (never trusted from the client) and passed down to
 * {@link DailyDispatchService}, which owns all repository access and business validation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/daily-dispatch")
@RequiredArgsConstructor
public class DailyDispatchController {

    private static final String LEADS = Authz.LEADS;

    private final DailyDispatchService dailyDispatchService;

    /**
     * POST /api/v1/daily-dispatch -- TL curates today's visit list for one FO.
     */
    @PostMapping
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<List<AllocationResponse>>> createDispatch(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateDailyDispatchRequest request) {

        UUID orgId = requireOrgId(principal);
        List<AllocationResponse> cases = dailyDispatchService.createDispatch(orgId, principal.getId(), request);
        if (cases.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.of("Dispatch cleared", cases));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Dispatched " + cases.size() + " cases", cases));
    }

    /**
     * GET /api/v1/daily-dispatch/me?date=YYYY-MM-DD -- FO fetches their own list.
     * Defaults to today if `date` is omitted.
     */
    // SYSTEM 09 TASK 9.2: makes the pre-existing filter-chain authentication requirement
    // explicit -- self-service, principal.getId() throughout.
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AllocationResponse>>> myList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = date != null ? date : LocalDate.now();
        UUID orgId = requireOrgId(principal);
        return ResponseEntity.ok(ApiResponse.success(
                dailyDispatchService.getListForAgent(orgId, principal.getId(), target)));
    }

    /**
     * GET /api/v1/daily-dispatch/agent/{agentId}?date=… -- TL views one FO's list.
     */
    @GetMapping("/agent/{agentId}")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<List<AllocationResponse>>> agentList(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = date != null ? date : LocalDate.now();
        UUID orgId = requireOrgId(principal);
        return ResponseEntity.ok(ApiResponse.success(
                dailyDispatchService.getListForAgent(orgId, agentId, target)));
    }

    /**
     * DELETE /api/v1/daily-dispatch/case -- TL drops a single entry off the list.
     */
    @DeleteMapping("/case")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<Void>> removeCase(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RemoveDispatchCaseRequest payload) {

        UUID orgId = requireOrgId(principal);
        dailyDispatchService.removeCase(orgId, payload.getAgentId(), payload.getDate(), payload.getAllocationId());
        return ResponseEntity.ok(ApiResponse.of("Dispatch entry removed", null));
    }

    /**
     * GET /api/v1/daily-dispatch/org/summary?orgId=…&date=… -- count of dispatched
     * entries for the org on a given date (defaults to today).
     */
    @GetMapping("/org/summary")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<Map<String, Long>>> orgSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = date != null ? date : LocalDate.now();
        UUID orgId = requireOrgId(principal);
        long dispatched = dailyDispatchService.countDispatchedForOrg(orgId, target);
        return ResponseEntity.ok(ApiResponse.success(Map.of("dispatched", dispatched)));
    }

    private UUID requireOrgId(UserPrincipal principal) {
        UUID orgId = principal.getOrganizationId();
        if (orgId == null) {
            throw new BusinessException("Caller has no organization context");
        }
        return orgId;
    }
}
