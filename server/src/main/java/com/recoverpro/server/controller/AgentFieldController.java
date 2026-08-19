package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.request.AgentSyncRequest;
import com.recoverpro.server.dto.request.LocationPingRequest;
import com.recoverpro.server.dto.request.ResolveIncidentRequest;
import com.recoverpro.server.dto.request.SosRequest;
import com.recoverpro.server.dto.request.StartShiftRequest;
import com.recoverpro.server.dto.response.AgentLiveStatusResponse;
import com.recoverpro.server.dto.response.AgentShiftResponse;
import com.recoverpro.server.dto.response.AgentSyncResponse;
import com.recoverpro.server.dto.response.IncidentReportResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AgentFieldService;
import com.recoverpro.server.service.AgentSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Field-agent shift / live-location / SOS endpoints (design-doc §7.3).
 *
 *   POST /api/v1/agent/shifts/start
 *   POST /api/v1/agent/shifts/end
 *   GET  /api/v1/agent/shifts/current
 *   POST /api/v1/agent/location          (only during ACTIVE shift)
 *   POST /api/v1/agent/sos
 *   PATCH /api/v1/agent/incidents/{id}/resolve
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentFieldController {

    private final AgentFieldService agentFieldService;
    private final AgentSyncService agentSyncService;

    private static final String FIELD_AGENT = Authz.FO_AND_ADMINS;
    private static final String LEADS = Authz.LEADS;

    /**
     * Batched offline sync (design-doc §7.6). Mobile client POSTs its
     * queued events here when connectivity returns; per-item failures
     * don't fail the batch.
     */
    @PostMapping("/sync")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<AgentSyncResponse>> sync(
            @Valid @RequestBody AgentSyncRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/agent/sync -- items={}", request.getItems().size());
        return ResponseEntity.ok(ApiResponse.success(
                agentSyncService.process(request, principal == null ? null : principal.getId())));
    }

    @PostMapping("/shifts/start")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<AgentShiftResponse>> startShift(
            @Valid @RequestBody StartShiftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(agentFieldService.startShift(request)));
    }

    @PostMapping("/shifts/end")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<AgentShiftResponse>> endShift(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(agentFieldService.endShift(principal.getId())));
    }

    @GetMapping("/shifts/current")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<AgentShiftResponse>> currentShift(
            @RequestParam UUID agentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(agentFieldService.currentShift(agentId, principal.getId())));
    }

    @PostMapping("/location")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<Void>> recordPing(
            @Valid @RequestBody LocationPingRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        agentFieldService.recordPing(request, principal.getId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/sos")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<IncidentReportResponse>> sos(
            @Valid @RequestBody SosRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.warn("SOS endpoint hit for agent {}", principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(agentFieldService.triggerSos(request, principal.getId())));
    }

    @PostMapping("/incidents/{id}/cancel")
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<Void>> cancelSos(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        agentFieldService.cancelSos(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping(value = "/sos/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FIELD_AGENT)
    public ResponseEntity<ApiResponse<Void>> uploadSosAudio(
            @RequestPart("audio") MultipartFile audio,
            @AuthenticationPrincipal UserPrincipal principal) {
        agentFieldService.storeSosAudio(principal.getId(), audio);
        log.info("POST /api/v1/agent/sos/audio -- agent={} size={}", principal.getId(), audio.getSize());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/incidents/{id}/resolve")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<IncidentReportResponse>> resolveIncident(
            @PathVariable UUID id,
            @Valid @RequestBody ResolveIncidentRequest body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                agentFieldService.resolveIncident(id, principal.getId(), body.getNotes())));
    }

    /** Supervisor live-view: agents currently on shift + their last ping. */
    @GetMapping("/active")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<List<AgentLiveStatusResponse>>> listActive(
            @RequestParam UUID orgId) {
        return ResponseEntity.ok(ApiResponse.success(agentFieldService.listActiveAgents(orgId)));
    }

    /** Supervisor incident triage list. */
    @GetMapping("/incidents")
    @PreAuthorize(LEADS)
    public ResponseEntity<ApiResponse<PagedResponse<IncidentReportResponse>>> listIncidents(
            @RequestParam UUID orgId,
            @RequestParam(defaultValue = "true") boolean unresolvedOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<IncidentReportResponse> result = agentFieldService.listIncidents(
                orgId, unresolvedOnly, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("triggeredAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }
}