package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.CloseSessionRequest;
import com.recoverpro.server.dto.request.PingRequest;
import com.recoverpro.server.dto.request.StartVisitRequest;
import com.recoverpro.server.dto.request.VisitTransitionRequest;
import com.recoverpro.server.dto.response.DistanceSummaryEntry;
import com.recoverpro.server.dto.response.TeamStatusEntry;
import com.recoverpro.server.dto.response.VisitSessionResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.VisitSessionService;
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
import java.util.Optional;
import java.util.UUID;

// SYSTEM 09 TASK 9.2: no class had this before -- SecurityConfig's filter chain already requires
// authentication for this whole path (not in PUBLIC_PATHS), so this isn't a behavior change, just
// making that requirement explicit at the method-security layer too. Everything below except
// teamStatus/distanceSummary is a field agent managing their own visit session
// (principal.getId() throughout) -- no role beyond "authenticated" is meaningful here. Those two
// methods keep their own more specific @PreAuthorize, which Spring Security applies instead of
// this class-level one, not in addition to it.
@Slf4j
@RestController
@RequestMapping("/api/v1/visit-sessions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class VisitSessionController {

    private final VisitSessionService visitSessionService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PostMapping
    public ResponseEntity<ApiResponse<VisitSessionResponse>> start(
            @Valid @RequestBody StartVisitRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        VisitSessionResponse resp = visitSessionService.startVisit(
                principal.getId(), principal.getOrganizationId(), request);
        log.info("POST /visit-sessions agent={} allocation={}", principal.getId(), request.getAllocationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of("Visit started", resp));
    }

    @PostMapping("/{id}/reached")
    public ResponseEntity<ApiResponse<VisitSessionResponse>> reached(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) VisitTransitionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (request == null) request = new VisitTransitionRequest();
        VisitSessionResponse resp = visitSessionService.markReached(id, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.of("Marked as reached", resp));
    }

    @PostMapping("/{id}/waiting")
    public ResponseEntity<ApiResponse<VisitSessionResponse>> waiting(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) VisitTransitionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (request == null) request = new VisitTransitionRequest();
        VisitSessionResponse resp = visitSessionService.markWaiting(id, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.of("Marked as waiting", resp));
    }

    @PostMapping("/{id}/ping")
    public ResponseEntity<ApiResponse<Map<String, Double>>> ping(
            @PathVariable UUID id,
            @Valid @RequestBody PingRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        double distance = visitSessionService.ping(id, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("distanceMetres", distance)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<VisitSessionResponse>> close(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CloseSessionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (request == null) request = new CloseSessionRequest();
        VisitSessionResponse resp = visitSessionService.closeSession(id, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.of("Visit closed", resp));
    }

    @PostMapping("/{id}/abandon")
    public ResponseEntity<ApiResponse<VisitSessionResponse>> abandon(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        VisitSessionResponse resp = visitSessionService.abandonSession(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Visit abandoned", resp));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<VisitSessionResponse>> getActive(
            @AuthenticationPrincipal UserPrincipal principal) {
        Optional<VisitSessionResponse> active = visitSessionService.getActive(principal.getId());
        return ResponseEntity.ok(ApiResponse.success(active.orElse(null)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<VisitSessionResponse>>> getToday(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(visitSessionService.getToday(principal.getId())));
    }

    @GetMapping("/by-visit-log/{visitLogId}")
    public ResponseEntity<ApiResponse<VisitSessionResponse>> getByVisitLog(
            @PathVariable UUID visitLogId) {
        Optional<VisitSessionResponse> session = visitSessionService.getByVisitLogId(visitLogId);
        return ResponseEntity.ok(ApiResponse.success(session.orElse(null)));
    }

    @GetMapping("/team-status")
    @PreAuthorize(Authz.LEADS)
    public ResponseEntity<ApiResponse<List<TeamStatusEntry>>> teamStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        UUID targetOrgId = resolveOrgId(principal, orgId, reason, "visitSessions:teamStatus");
        return ResponseEntity.ok(ApiResponse.success(
                visitSessionService.getTeamStatus(targetOrgId, queryDate)));
    }

    @GetMapping("/distance-summary")
    @PreAuthorize(Authz.LEADS)
    public ResponseEntity<ApiResponse<List<DistanceSummaryEntry>>> distanceSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        UUID targetOrgId = resolveOrgId(principal, orgId, reason, "visitSessions:distanceSummary");
        return ResponseEntity.ok(ApiResponse.success(
                visitSessionService.getDistanceSummary(targetOrgId, queryDate)));
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Both endpoints always queried principal.getOrganizationId() directly, which is NULL for a
     * platform admin and never NULL for anyone else -- there was no orgId parameter at all, so a
     * platform admin had no way to target any org. visit_sessions already has the platform-admin RLS
     * bypass (V063, fixed for ReportingController), but sessionRepo.findByOrgIdAndStartedAtBetween(null,
     * ...) would still return nothing (no real row has org_id IS NULL) even with the bypass active,
     * since orgId itself was never a real target -- this needed a parameter to supply one, not just
     * an RLS fix. Target org is known up front here, so this uses the reason-requiring
     * beginCrossOrgAccess, matching ReportingController's identical fix.
     */
    private UUID resolveOrgId(UserPrincipal principal, UUID requestedOrgId, String reason, String resource) {
        if (isPlatformAdmin(principal)) {
            if (requestedOrgId == null) {
                throw new BusinessException("Platform admins must specify ?orgId= to view a tenant's team status.");
            }
            platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), requestedOrgId, reason, resource);
            return requestedOrgId;
        }
        return principal.getOrganizationId();
    }
}
