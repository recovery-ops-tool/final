package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.VisitApprovalRequest;
import com.recoverpro.server.dto.request.VisitLogRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.VisitLogResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.dto.response.VisitImportResult;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.IdempotencyKeyService;
import com.recoverpro.server.service.VisitImportService;
import com.recoverpro.server.service.VisitLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/visit-logs")
@RequiredArgsConstructor
public class VisitLogController {

    private static final String SUBMITTERS =
            "hasAnyRole('FO')";

    private static final String READERS = Authz.ALL_STAFF;

    private static final String LEADS = Authz.LEADS;

    private final VisitLogService visitLogService;
    private final AllocationService allocationService;
    private final IdempotencyKeyService idempotencyKeyService;
    private final com.recoverpro.server.repository.AllocationRepository allocationRepository;
    private final com.recoverpro.server.repository.VisitLogRepository visitLogRepository;
    private final com.recoverpro.server.mapper.VisitLogMapper visitLogMapper;
    private final VisitImportService visitImportService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    // TASK 26.2: Pageable's Sort is bound directly from the client's own ?sort= query param by
    // Spring's PageableHandlerMethodArgumentResolver, with no allowlisting of its own -- sanitized
    // via SafeSort.sanitize() before reaching visitLogRepository.findBy*Paged (contactPerson/
    // contactNumber are EncryptedStringConverter fields, deliberately excluded).
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "visitDate", "visitDate",
            "visitTime", "visitTime",
            "createdAt", "createdAt");

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<VisitLogResponse>>> listVisits(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "visitDate") Pageable pageable) {
        pageable = SafeSort.sanitize(pageable, SORTABLE_FIELDS, "visitDate", Sort.Direction.ASC);

        // FOs see only their own visits; leads see the whole org
        if (isOnlyFieldOfficer(principal)) {
            Page<VisitLogResponse> page = visitLogService.getByAgentIdPaged(principal.getId(), pageable);
            return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(page)));
        }

        UUID orgId = principal.getOrganizationId();
        if (orgId == null) {
            throw new BusinessException("Caller has no organization context");
        }

        Page<VisitLogResponse> page = visitLogService.getByOrganizationIdPaged(orgId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(page)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<VisitLogResponse>> create(
            @RequestPart("data") @Valid VisitLogRequest request,
            @RequestPart(value = "image1", required = false) MultipartFile image1,
            @RequestPart(value = "image2", required = false) MultipartFile image2,
            @RequestPart(value = "image3", required = false) MultipartFile image3,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID callerOrg = principal.getOrganizationId();
        if (callerOrg == null) {
            throw new BusinessException("Caller has no organization context");
        }

        if (idempotencyKey != null) {
            IdempotencyKeyService.IdempotencyResult idemResult =
                    idempotencyKeyService.tryClaim("visit:submit", idempotencyKey, request.getAllocationId());
            if (idemResult == IdempotencyKeyService.IdempotencyResult.REPLAY) {
                log.info("Idempotent replay for POST /visit-logs key={}", idempotencyKey);
                return ResponseEntity.ok(ApiResponse.of("Visit log already submitted", null));
            }
            if (idemResult == IdempotencyKeyService.IdempotencyResult.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.of("Idempotency key already used for a different visit", null));
            }
        }

        request.setOrganizationId(callerOrg);

        AllocationResponse alloc =
                allocationService.getAllocationById(request.getAllocationId());

        if (alloc.getOrganizationId() == null ||
                !alloc.getOrganizationId().equals(callerOrg)) {
            throw new ResourceNotFoundException(
                    "Allocation not found: " + request.getAllocationId());
        }

        log.info("POST /visit-logs allocation={} agent={}",
                request.getAllocationId(), principal.getId());

        VisitLogResponse response = visitLogService.create(
                request, image1, image2, image3,
                principal.getId(), principal.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Visit log created successfully", response));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Authz.ADMINS)
    public ResponseEntity<ApiResponse<VisitImportResult>> importVisits(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID targetOrgId = resolveImportOrgId(principal, orgId, reason);

        log.info("POST /visit-logs/import file={} org={} by={}",
                file.getOriginalFilename(), targetOrgId, principal.getId());

        VisitImportResult result = visitImportService.importFromExcel(file, targetOrgId, principal.getId());
        String message = result.getImported() + " visits imported, " + result.getFailed() + " failed";
        return ResponseEntity.ok(ApiResponse.of(message, result));
    }

    /**
     * importFromExcel assumes a real, non-null organizationId throughout (it dereferences it
     * unconditionally, including a bare .equals() call that would NPE) -- but this endpoint took no
     * orgId parameter at all, so a platform admin (whose own organizationId is always null) had no
     * way to supply one. Every platform-admin import attempt crashed with a NullPointerException
     * partway through processing. Target org is known up front for this endpoint (unlike the by-id
     * reads below), so this uses the reason-requiring beginCrossOrgAccess.
     */
    private UUID resolveImportOrgId(UserPrincipal principal, UUID requestedOrgId, String reason) {
        if (isPlatformAdmin(principal)) {
            if (requestedOrgId == null) {
                throw new BusinessException("Platform admins must specify ?orgId= to import visits into a tenant.");
            }
            platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), requestedOrgId, reason, "visitLogs:import");
            return requestedOrgId;
        }
        UUID orgId = principal.getOrganizationId();
        if (orgId == null) {
            throw new BusinessException("Caller has no organization context");
        }
        return orgId;
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<VisitLogResponse>> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        elevateIfPlatformAdmin(principal, "visitLogs:getById:" + id);
        VisitLogResponse resp = visitLogService.getById(id);
        assertSameTenant(resp.getOrganizationId(), principal);

        if (isOnlyFieldOfficer(principal) &&
                !principal.getId().equals(resp.getAgentId())) {
            throw new ResourceNotFoundException("Visit log not found");
        }

        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @GetMapping("/today")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<List<VisitLogResponse>>> getTodayVisits(
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(
                ApiResponse.success(
                        visitLogService.getTodayVisits(principal.getId())
                )
        );
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<VisitLogResponse>>> getByAllocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID allocationId) {

        elevateIfPlatformAdmin(principal, "visitLogs:byAllocation:" + allocationId);
        AllocationResponse alloc = allocationService.getAllocationById(allocationId);
        assertSameTenant(alloc.getOrganizationId(), principal);

        // Collect IDs of every allocation sharing the same loan number in this org
        // so that visits from previous allocations (re-allocations) are included.
        List<UUID> allocationIds;
        if (alloc.getLoanNumber() != null && !alloc.getLoanNumber().isBlank()) {
            allocationIds = allocationRepository.findIdsByOrganizationIdAndLoanNumber(
                    alloc.getOrganizationId(), alloc.getLoanNumber());
        } else {
            allocationIds = List.of(allocationId);
        }

        List<VisitLogResponse> visits = visitLogRepository
                .findAllByAllocationIdsOrdered(allocationIds)
                .stream()
                .map(visitLogMapper::toResponse)
                .collect(Collectors.toList());

        if (isOnlyFieldOfficer(principal)) {
            visits = visits.stream()
                    .filter(v -> principal.getId().equals(v.getAgentId()))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(ApiResponse.success(visits));
    }

    @GetMapping("/allocation/{allocationId}/last-location")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLastLocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID allocationId) {

        elevateIfPlatformAdmin(principal, "visitLogs:lastLocation:" + allocationId);
        AllocationResponse alloc = allocationService.getAllocationById(allocationId);
        assertSameTenant(alloc.getOrganizationId(), principal);

        Optional<Map<String, Object>> loc = visitLogService.getLastLocation(allocationId);
        if (loc.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(loc.get()));
        }
        return ResponseEntity.ok(ApiResponse.success(null, "No location recorded for this allocation"));
    }

    @GetMapping("/allocation/{allocationId}/last-address")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<Map<String, String>>> getLastVisitedAddress(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID allocationId) {

        elevateIfPlatformAdmin(principal, "visitLogs:lastAddress:" + allocationId);
        AllocationResponse alloc =
                allocationService.getAllocationById(allocationId);

        assertSameTenant(alloc.getOrganizationId(), principal);

        Optional<String> address =
                visitLogService.getLastVisitedAddress(allocationId);

        if (address.isPresent()) {
            return ResponseEntity.ok(
                    ApiResponse.success(
                            Map.of("lastVisitedAddress", address.get())
                    )
            );
        }

        return ResponseEntity.ok(
                ApiResponse.success(null, "No previous visit found")
        );
    }

    @GetMapping("/agent/{agentId}")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<PagedResponse<VisitLogResponse>>> getByAgent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID agentId,
            @PageableDefault(size = 20, sort = "visitDate") Pageable pageable) {
        pageable = SafeSort.sanitize(pageable, SORTABLE_FIELDS, "visitDate", Sort.Direction.ASC);

        elevateIfPlatformAdmin(principal, "visitLogs:byAgent:" + agentId);
        Page<VisitLogResponse> page =
                visitLogService.getByAgentIdPaged(agentId, pageable);

        return ResponseEntity.ok(
                ApiResponse.success(PagedResponse.from(page))
        );
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<VisitLogResponse>> approve(
            @PathVariable UUID id,
            @RequestBody @Valid VisitApprovalRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "visitLogs:approve:" + id);
        VisitLogResponse current = visitLogService.getById(id);
        assertSameTenant(current.getOrganizationId(), principal);

        log.info("PATCH /visit-logs/{}/approve action={} by={}",
                id, request.getAction(), principal.getId());

        return ResponseEntity.ok(ApiResponse.success(
                visitLogService.approveVisit(id, request, principal.getId())
        ));
    }

    @GetMapping("/{id}/image/{seq}/url")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<String>> getImageUrl(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable int seq) {

        if (seq < 1 || seq > 3) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.of("Image sequence must be 1, 2 or 3", null));
        }

        elevateIfPlatformAdmin(principal, "visitLogs:imageUrl:" + id);
        VisitLogResponse current = visitLogService.getById(id);
        assertSameTenant(current.getOrganizationId(), principal);

        if (isOnlyFieldOfficer(principal) &&
                !principal.getId().equals(current.getAgentId())) {
            throw new ResourceNotFoundException("Visit log not found");
        }

        return ResponseEntity.ok(
                ApiResponse.success(
                        visitLogService.regenerateSignedUrl(id, seq)
                )
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Authz.ADMINS)
    public ResponseEntity<ApiResponse<Void>> softDelete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "visitLogs:softDelete:" + id);
        VisitLogResponse current = visitLogService.getById(id);
        assertSameTenant(current.getOrganizationId(), principal);

        visitLogService.softDelete(id, principal.getId());

        return ResponseEntity.ok(
                ApiResponse.of("Visit log deleted", null)
        );
    }

    // ─── Tenant Guards ────────────────────────────────────────────────────

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private boolean isOnlyFieldOfficer(UserPrincipal p) {
        boolean hasFo = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_FO".equals(a.getAuthority()));

        boolean hasLead = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TL".equals(a.getAuthority())
                        || "ROLE_MANAGER".equals(a.getAuthority())
                        || "ROLE_ORG_ADMIN".equals(a.getAuthority())
                        || "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));

        return hasFo && !hasLead;
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;

        if (resourceOrg == null ||
                !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Visit log not found");
        }
    }

    /**
     * visit_logs and allocations both already have a platform-admin RLS bypass (V063, fixed for
     * ReportingController earlier this pass) -- but nothing here ever called PlatformAdminAccessGuard
     * to activate it, so assertSameTenant's "if platform admin, skip" branch was dead: the fetch
     * before it already came back empty under RLS.
     */
    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }
}
