package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.CompleteCallRequest;
import com.recoverpro.server.dto.request.StartCallRequest;
import com.recoverpro.server.dto.response.CallLogResponse;
import com.recoverpro.server.dto.response.CallStartResponse;
import com.recoverpro.server.enums.CallOutcome;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.CallLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/call-logs")
@RequiredArgsConstructor
public class CallLogController {

    private static final String SUBMITTERS = "hasAnyRole('FO','CALLER')";
    private static final String READERS = Authz.ALL_STAFF;

    private final CallLogService callLogService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PostMapping("/start")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<CallStartResponse>> start(
            @Valid @RequestBody StartCallRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID orgId = requireOrgId(principal);
        CallStartResponse response = callLogService.startCall(request.getAllocationId(), principal.getId(), orgId);
        log.info("POST /call-logs/start allocation={} agent={}", request.getAllocationId(), principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of("Call started", response));
    }

    @PostMapping("/{id}/recording")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<Void>> uploadRecording(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID orgId = requireOrgId(principal);
        callLogService.attachRecording(id, orgId, file);
        return ResponseEntity.ok(ApiResponse.of("Recording attached", null));
    }

    @PatchMapping("/{id}/complete")
    @PreAuthorize(SUBMITTERS)
    public ResponseEntity<ApiResponse<CallLogResponse>> complete(
            @PathVariable UUID id,
            @Valid @RequestBody CompleteCallRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID orgId = requireOrgId(principal);
        CallLogResponse response = callLogService.completeCall(id, orgId, request);
        return ResponseEntity.ok(ApiResponse.of("Call completed", response));
    }

    @GetMapping("/allocation/{allocationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<CallLogResponse>>> getByAllocation(
            @PathVariable UUID allocationId,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID effectiveOrgId = resolveOrgId(principal, orgId, reason, "callLogs:byAllocation:" + allocationId);
        return ResponseEntity.ok(ApiResponse.success(callLogService.getByAllocation(allocationId, effectiveOrgId)));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<CallLogResponse>>> getCallLogs(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) CallOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID effectiveOrgId = resolveOrgId(principal, orgId, reason, "callLogs:list");
        // Callers who only place calls (FO/CALLER, no lead/admin role) are scoped to their
        // own agentId server-side — the org-wide list is a management view.
        UUID effectiveAgentId = isSelfScopedCaller(principal) ? principal.getId() : agentId;

        Instant fromInstant = fromDate != null ? fromDate.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant toInstant   = toDate   != null ? toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;

        Page<CallLogResponse> result = callLogService.getForOrg(
                effectiveOrgId, effectiveAgentId, outcome, fromInstant, toInstant, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/{id}/recording")
    @PreAuthorize(Authz.LEADS)
    public ResponseEntity<Resource> downloadRecording(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = requireOrgId(principal);
        Resource resource = callLogService.downloadRecording(id, orgId);

        ContentDisposition disposition = ContentDisposition.inline().filename(id + ".m4a").build();
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("audio/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private boolean isSelfScopedCaller(UserPrincipal p) {
        boolean hasCaller = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_FO".equals(a.getAuthority()) || "ROLE_CALLER".equals(a.getAuthority()));
        boolean hasLead = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TL".equals(a.getAuthority())
                        || "ROLE_MANAGER".equals(a.getAuthority())
                        || "ROLE_ORG_ADMIN".equals(a.getAuthority())
                        || "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        return hasCaller && !hasLead;
    }

    private UUID requireOrgId(UserPrincipal principal) {
        UUID orgId = principal.getOrganizationId();
        if (orgId == null) {
            throw new BusinessException("Caller has no organization context");
        }
        return orgId;
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private UUID resolveOrgId(UserPrincipal principal, UUID requestedOrgId, String reason, String resource) {
        if (isPlatformAdmin(principal)) {
            if (requestedOrgId == null) {
                throw new BusinessException("Platform admins must specify ?orgId= to view a tenant's call logs.");
            }
            platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), requestedOrgId, reason, resource);
            return requestedOrgId;
        }
        return requireOrgId(principal);
    }
}
