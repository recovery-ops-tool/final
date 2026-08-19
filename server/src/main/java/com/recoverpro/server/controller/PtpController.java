package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.CreatePtpRequest;
import com.recoverpro.server.dto.request.PtpFilterRequest;
import com.recoverpro.server.dto.request.UpdatePtpStatusRequest;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.response.*;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.enums.PtpStatus;
import com.recoverpro.server.exception.IdempotencyKeyConflictException;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.*;
import com.recoverpro.server.service.IdempotencyKeyService.IdempotencyResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/ptps")
@RequiredArgsConstructor
public class PtpController {

    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "createdAt", "createdAt",
            "promiseDate", "promiseDate",
            "amount", "amount",
            "status", "status");

    private static final String SUBMITTERS = "hasAnyRole('FO','CALLER')";
    private static final String READERS = Authz.ALL_STAFF;
    private static final String LEADS = Authz.LEADS;
    private static final String ADMINS = Authz.ADMINS;
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    // Row count above which a CSV export is flagged to platform admins as a mass data export.
    private static final int MASS_EXPORT_ROW_THRESHOLD = 10_000;

    private final PtpService ptpService;
    private final IdempotencyKeyService idempotencyKeyService;
    private final AllocationService allocationService;
    private final com.recoverpro.server.repository.AllocationRepository allocationRepository;
    private final UserRepository userRepository;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;
    private final NotificationService notificationService;

    /**
     * ptp_records' RLS policy (V061) only grants cross-org visibility once
     * app.is_platform_admin is set for the request -- several methods below have always had
     * "if platform admin, show cross-org data" logic, but it was dead until this elevation existed:
     * ptpService's underlying queries came back empty for a platform admin before that logic could
     * ever matter. Uses the unattended escape hatch (no reason prompt) rather than
     * beginCrossOrgAccess because none of these callers have a single target org known up front --
     * getAll/exportCsv span every org by design, and the by-id lookups don't know which org they'll
     * land on until after the elevated fetch succeeds.
     */
    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }

    private boolean shouldProceed(String key, String scope, UUID id) {
        if (key == null || key.isBlank()) return true;

        IdempotencyResult result =
                idempotencyKeyService.tryClaim(scope, key, id);

        return switch (result) {
            case CLAIMED -> true;
            case REPLAY -> false;
            case CONFLICT -> throw new IdempotencyKeyConflictException(
                    "Idempotency-Key already used against a different PTP");
        };
    }

    @PostMapping
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<PtpResponse>> createPtp(
            @Valid @RequestBody CreatePtpRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (request.getAllocationId() != null) {
            AllocationResponse alloc =
                    allocationService.getAllocationById(request.getAllocationId());
            assertSameTenant(alloc.getOrganizationId(), principal);
        }

        if (key != null && !key.isBlank()) {
            UUID existing = idempotencyKeyService.getCollectionId(key);
            if (existing != null) {
                log.info("Replay create key={} id={}", key, existing);
                return ResponseEntity.ok(ApiResponse.success(
                        ptpService.getPtpById(existing),
                        "PTP created successfully (replay)."));
            }
        }

        PtpResponse resp =
                ptpService.createPtp(request, principal.getId());

        if (key != null && !key.isBlank()) {
            idempotencyKeyService.registerKey(key, resp.getId());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(resp, "PTP created successfully."));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<PtpResponse>>> getByAllocation(
            @PathVariable UUID allocationId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertViaAllocation(allocationId, principal);
        AllocationResponse alloc = allocationService.getAllocationById(allocationId);

        List<java.util.UUID> allocationIds =
                (alloc.getLoanNumber() != null && !alloc.getLoanNumber().isBlank())
                        ? allocationRepository.findIdsByOrganizationIdAndLoanNumber(
                                alloc.getOrganizationId(), alloc.getLoanNumber())
                        : java.util.List.of(allocationId);

        elevateIfPlatformAdmin(principal, "ptp:byAllocation:" + allocationId);
        List<PtpResponse> ptps = ptpService.getPtpsByAllocationIds(allocationIds);

        if (isOnlyFieldOfficer(principal)) {
            UUID user = principal.getId();
            ptps = ptps.stream().filter(p -> user.equals(p.getAgentId())).toList();
        }

        return ResponseEntity.ok(ApiResponse.success(ptps));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PtpResponse>> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        elevateIfPlatformAdmin(principal, "ptp:byId:" + id);
        PtpResponse resp = ptpService.getPtpById(id);
        assertViaAllocation(resp.getAllocationId(), principal);

        if (isOnlyFieldOfficer(principal) &&
                !principal.getId().equals(resp.getAgentId())) {
            throw new ResourceNotFoundException("PTP not found");
        }

        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<PtpHistoryResponse>>> getPtpHistory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        elevateIfPlatformAdmin(principal, "ptp:history:" + id);
        PtpResponse resp = ptpService.getPtpById(id);
        assertViaAllocation(resp.getAllocationId(), principal);

        if (isOnlyFieldOfficer(principal) &&
                !principal.getId().equals(resp.getAgentId())) {
            throw new ResourceNotFoundException("PTP not found");
        }

        return ResponseEntity.ok(ApiResponse.success(ptpService.getPtpHistory(id)));
    }

    @GetMapping("/allocation/{allocationId}/history")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<PtpHistoryResponse>>> getFullAllocationHistory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID allocationId) {

        assertViaAllocation(allocationId, principal);
        elevateIfPlatformAdmin(principal, "ptp:fullAllocationHistory:" + allocationId);
        return ResponseEntity.ok(ApiResponse.success(ptpService.getFullAllocationHistory(allocationId)));
    }

    @GetMapping("/agents/{agentId}/statistics")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<PtpStatisticsResponse>> getAgentStatistics(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID agentId) {

        User agent = userRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found: " + agentId));

        if (!isPlatformAdmin(principal)) {
            UUID callerOrg = principal.getOrganizationId();
            if (callerOrg == null || !callerOrg.equals(agent.getOrganizationId())) {
                throw new ResourceNotFoundException("Agent not found: " + agentId);
            }
        }

        return ResponseEntity.ok(ApiResponse.success(ptpService.getAgentStatistics(agentId)));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<PtpResponse>>> getAll(
            @AuthenticationPrincipal UserPrincipal principal,
            PtpFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String dir) {

        Sort sort = SafeSort.from(sortBy, dir, SORTABLE_FIELDS, "createdAt");

        elevateIfPlatformAdmin(principal, "ptp:list");
        Page<PtpResponse> result =
                ptpService.getAllPtps(filter, PageRequest.of(page, size, sort));

        if (!isPlatformAdmin(principal)) {
            List<PtpResponse> filtered = scopeToPrincipal(principal, result.getContent());

            return ResponseEntity.ok(ApiResponse.success(
                    PagedResponse.<PtpResponse>builder()
                            .content(filtered)
                            .page(page)
                            .size(size)
                            .totalElements(result.getTotalElements())
                            .totalPages(result.getTotalPages())
                            .last(result.isLast())
                            .build()
            ));
        }

        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize(READERS)
    public ResponseEntity<StreamingResponseBody> exportCsv(
            @AuthenticationPrincipal UserPrincipal principal,
            PtpFilterRequest filter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        if (fromDate != null) filter.setPromisedDateFrom(fromDate);
        if (toDate != null) filter.setPromisedDateTo(toDate);

        elevateIfPlatformAdmin(principal, "ptp:export");
        Page<PtpResponse> result = ptpService.getAllPtps(filter, Pageable.unpaged());
        List<PtpResponse> rows = isPlatformAdmin(principal)
                ? result.getContent()
                : scopeToPrincipal(principal, result.getContent());

        if (rows.size() > MASS_EXPORT_ROW_THRESHOLD) {
            notificationService.createForPlatformRole(PlatformConstants.ROLE_PLATFORM_ADMIN,
                    NotificationType.PLATFORM_MASS_DATA_EXPORT,
                    "Large data export performed",
                    principal.getEmail() + " exported " + rows.size() + " PTP records.");
        }

        StreamingResponseBody body = out -> {
            try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write("Promised Date,Promised Amount,Collected Amount,Status,Loan Number,"
                        + "Borrower Name,Agent Name,Reminder Sent,Created At\n");
                for (PtpResponse p : rows) {
                    w.write(csvJoin(p.getPromisedDate(), p.getPromisedAmount(), p.getCollectedAmount(),
                            p.getStatus(), p.getLoanNumber(), p.getBorrowerName(), p.getAgentName(),
                            p.getReminderSent(), p.getCreatedAt()));
                }
                w.flush();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ptps.csv\"")
                .body(body);
    }

    private List<PtpResponse> scopeToPrincipal(UserPrincipal principal, List<PtpResponse> content) {
        if (isPlatformAdmin(principal)) return content;

        UUID org = principal.getOrganizationId();
        UUID user = principal.getId();
        boolean foOnly = isOnlyFieldOfficer(principal);

        return content.stream()
                .filter(p -> {
                    if (p.getAllocationId() == null) return false;
                    try {
                        AllocationResponse a =
                                allocationService.getAllocationById(p.getAllocationId());
                        if (!org.equals(a.getOrganizationId())) return false;
                        return !foOnly || user.equals(p.getAgentId());
                    } catch (Exception e) {
                        // SYSTEM 13 TASK 13.2: was silently excluded, indistinguishable from a
                        // legitimate cross-org filter -- a transient allocation-lookup failure
                        // (or a genuinely orphaned PTP) would make a real PTP vanish from the list
                        // with no trace anywhere that something went wrong, not just "filtered."
                        log.warn("PTP scoping: allocation lookup failed for ptpId={} allocationId={}, "
                                + "excluding from result: {}", p.getId(), p.getAllocationId(), e.getMessage());
                        return false;
                    }
                }).toList();
    }

    private static String csvJoin(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(fields[i]));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String csvEscape(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(LEADS + " or " + SUBMITTERS)
    public ResponseEntity<ApiResponse<PtpResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePtpStatusRequest req,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @AuthenticationPrincipal UserPrincipal principal) {

        PtpResponse cur = ptpService.getPtpById(id);
        assertViaAllocation(cur.getAllocationId(), principal);

        if (!shouldProceed(key, "ptp.statusUpdate", id)) {
            return ResponseEntity.ok(ApiResponse.success(cur));
        }

        return ResponseEntity.ok(ApiResponse.success(
                ptpService.updatePtpStatus(id, req,
                        principal.getId(), principal.getEmail())
        ));
    }

    // ─── Tenant Guards ───────────────────────────────────────────

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private boolean isOnlyFieldOfficer(UserPrincipal p) {
        boolean fo = p.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_FO"));

        boolean lead = p.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN")
                        || a.getAuthority().contains("MANAGER")
                        || a.getAuthority().contains("TL"));

        return fo && !lead;
    }

    private void assertSameTenant(UUID org, UserPrincipal p) {
        if (isPlatformAdmin(p)) return;

        if (org == null || !org.equals(p.getOrganizationId())) {
            throw new ResourceNotFoundException("PTP not found");
        }
    }

    private void assertViaAllocation(UUID allocId, UserPrincipal p) {
        if (allocId == null) return;

        AllocationResponse a;
        try {
            a = allocationService.getAllocationById(allocId);
        } catch (Exception e) {
            throw new ResourceNotFoundException("PTP not found");
        }

        assertSameTenant(a.getOrganizationId(), p);
    }
}