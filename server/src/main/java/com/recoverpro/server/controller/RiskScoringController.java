package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.response.BorrowerRiskScoreResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.RiskScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Risk scoring & segmentation (design-doc §8).
 *
 *   GET  /api/v1/risk/borrowers/{id}            latest score (200 / 404 if never scored)
 *   POST /api/v1/risk/borrowers/{id}/score      recompute with no extra features
 *   POST /api/v1/risk/borrowers/{id}/score-with-features
 *                                               recompute with caller-supplied features
 */
@RestController
@RequestMapping("/api/v1/risk/borrowers")
@RequiredArgsConstructor
@Slf4j
public class RiskScoringController {

    private final RiskScoringService riskScoringService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    private static final String ADMINS = Authz.ADMINS;

    @GetMapping("/{id}")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<BorrowerRiskScoreResponse>> getLatest(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "risk:getLatest:" + id);
        BorrowerRiskScoreResponse latest = riskScoringService.getLatest(id);
        if (latest == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Borrower has not been scored yet"));
        }
        return ResponseEntity.ok(ApiResponse.success(latest));
    }

    @PostMapping("/{id}/score")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<BorrowerRiskScoreResponse>> score(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/risk/borrowers/{}/score", id);
        elevateIfPlatformAdmin(principal, "risk:score:" + id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Risk score computed",
                        riskScoringService.scoreBorrower(id, callerOrgId(principal))));
    }

    // SYSTEM 07 TASK 7.4: deliberately NOT converted to a typed DTO -- this is a genuinely
    // open-ended scoring-feature bag whose actual key set varies by caller/model version; a
    // fixed DTO would over-constrain it. Admin-only (@PreAuthorize below), and
    // riskScoringService.scoreWithFeatures() is the actual validation boundary for what it does
    // with arbitrary keys.
    @PostMapping("/{id}/score-with-features")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<BorrowerRiskScoreResponse>> scoreWithFeatures(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> features,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/risk/borrowers/{}/score-with-features - keys={}",
                id, features == null ? null : features.keySet());
        elevateIfPlatformAdmin(principal, "risk:scoreWithFeatures:" + id);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                "Risk score computed with supplied features",
                riskScoringService.scoreWithFeatures(id, features, callerOrgId(principal))));
    }

    private static UUID callerOrgId(UserPrincipal principal) {
        return isPlatformAdmin(principal) ? null : principal.getOrganizationId();
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /**
     * borrowers and borrower_risk_scores (V066) both went fail-closed in V040 with no
     * platform-admin bypass -- score()/scoreWithFeatures() already pass callerOrgId=null for a
     * platform admin specifically to skip the app-layer ownership check, but that intent was
     * defeated by RLS filtering the borrower row (and the risk-score INSERT's implicit WITH CHECK)
     * before the app-layer logic ever got a say.
     */
    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }
}