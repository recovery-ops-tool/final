package com.recoverpro.server.controller;

import com.recoverpro.server.annotation.RequiresFeature;
import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.dto.request.ReportRequest;
import com.recoverpro.server.dto.response.*;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.enums.ReportType;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.dto.response.FoDayReportResponse;
import com.recoverpro.server.dto.response.MisEodReportResponse;
import com.recoverpro.server.service.MisEodReportService;
import com.recoverpro.server.service.ReportingService;
import com.recoverpro.server.util.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize(ReportingController.READERS)
public class ReportingController {

    static final String READERS = Authz.LEADS;

    private final ReportingService reportingService;
    private final MisEodReportService misEodReportService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;

    @GetMapping("/agent/{agentId}/performance")
    public ResponseEntity<ApiResponse<AgentPerformanceResponse>> getAgentPerformance(
            @PathVariable UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (orgId != null) authorizeOrgAccess(principal, orgId, reason, "reports:agentPerformance");
        return ResponseEntity.ok(ApiResponse.success(
                reportingService.getAgentPerformance(agentId, date != null ? date : LocalDate.now(), orgId)));
    }

    @GetMapping("/agent/rankings")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<PagedResponse<AgentPerformanceResponse>>> getAgentRankings(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:agentRankings");
        Page<AgentPerformanceResponse> result = reportingService.getAgentRankings(
                orgId, date != null ? date : LocalDate.now(),
                PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("efficiencyScore").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/team/performance")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<TeamPerformanceResponse>> getTeamPerformance(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:teamPerformance");
        LocalDate fromDate = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate toDate = to != null ? to : LocalDate.now();
        return ResponseEntity.ok(ApiResponse.success(reportingService.getTeamPerformance(orgId, fromDate, toDate)));
    }

    @GetMapping("/visit-completion/daily")
    public ResponseEntity<ApiResponse<DailyVisitCompletionResponse>> getDailyVisitCompletion(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:dailyVisitCompletion");
        return ResponseEntity.ok(ApiResponse.success(
                reportingService.getDailyVisitCompletion(orgId, date != null ? date : LocalDate.now())));
    }

    @GetMapping("/collection-efficiency")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<CollectionEfficiencyResponse>> getCollectionEfficiency(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:collectionEfficiency");
        LocalDate fromDate = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate toDate = to != null ? to : LocalDate.now();
        return ResponseEntity.ok(ApiResponse.success(reportingService.getCollectionEfficiency(orgId, fromDate, toDate)));
    }

    @GetMapping("/reassignment-frequency")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<ReassignmentFrequencyResponse>> getReassignmentFrequency(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:reassignmentFrequency");
        LocalDate fromDate = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate toDate = to != null ? to : LocalDate.now();
        return ResponseEntity.ok(ApiResponse.success(reportingService.getReassignmentFrequency(orgId, fromDate, toDate)));
    }

    @GetMapping("/loan-book/history")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<PagedResponse<MonthlyLoanBookResponse>>> getLoanBookHistory(
            @RequestParam UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:loanBookHistory");
        Page<MonthlyLoanBookResponse> result = reportingService.getMonthlyLoanBookHistory(
                orgId, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("snapshotMonth").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @GetMapping("/loan-book")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<MonthlyLoanBookResponse>> getLoanBook(
            @RequestParam UUID orgId,
            @RequestParam int month,
            @RequestParam int year,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:loanBook");
        return ResponseEntity.ok(ApiResponse.success(reportingService.getMonthlyLoanBook(orgId, month, year)));
    }

    @GetMapping("/bank-reconciliation")
    @RequiresFeature(PlanFeatureMatrix.ADVANCED_REPORTS)
    public ResponseEntity<ApiResponse<BankReconciliationResponse>> getBankReconciliation(
            @RequestParam UUID orgId,
            @RequestParam int month,
            @RequestParam int year,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:bankReconciliation");
        return ResponseEntity.ok(ApiResponse.success(reportingService.getBankReconciliation(orgId, month, year)));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<ReportJobResponse>> enqueueReport(
            @Valid @RequestBody ReportRequest request,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /reports/generate - type={} format={} orgId={}",
                request.getReportType(), request.getExportFormat(), request.getOrganizationId());
        // SYSTEM 07 TASK 7.3: keyed by the authenticated user -- each call enqueues a real
        // background job (DB aggregation + export file generation via ReportJobExecutor).
        AppProperties.Security sec = appProperties.getSecurity();
        String rateLimitKey = "report:" + principal.getId();
        if (!rateLimiter.isAllowed(rateLimitKey, sec.getReportGenerateMaxAttempts(), sec.getReportGenerateWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(rateLimitKey);
            throw new RateLimitExceededException(
                    "Too many report requests. Try again in " + retryAfter + "s.", retryAfter);
        }
        authorizeOrgAccess(principal, request.getOrganizationId(), reason, "reports:generate");
        ReportJobResponse job = reportingService.enqueueReport(request, principal.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of("Report generation queued", job));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<ReportJobResponse>> getJobStatus(
            @PathVariable UUID jobId,
            @RequestParam UUID orgId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:jobStatus:" + jobId);
        return ResponseEntity.ok(ApiResponse.success(reportingService.getJobStatus(jobId, orgId)));
    }

    @GetMapping("/fo-day")
    public ResponseEntity<ApiResponse<FoDayReportResponse>> getFoDay(
            @RequestParam UUID orgId,
            @RequestParam UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:foDay:" + agentId);
        return ResponseEntity.ok(ApiResponse.success(
                misEodReportService.getFoDay(orgId, agentId, date != null ? date : LocalDate.now())));
    }

    @GetMapping("/mis-eod")
    public ResponseEntity<ApiResponse<MisEodReportResponse>> getMisEod(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:misEod");
        LocalDate reportDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(ApiResponse.success(misEodReportService.generate(orgId, reportDate)));
    }

    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<PagedResponse<ReportJobResponse>>> getJobs(
            @RequestParam UUID orgId,
            @RequestParam(required = false) ReportType type,
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        authorizeOrgAccess(principal, orgId, reason, "reports:jobs");
        Page<ReportJobResponse> result = reportingService.getJobs(
                orgId, type, status, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("createdAt").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Every report here takes an explicit orgId, and MANAGER/TL/ORG_ADMIN callers had no app-layer
     * check that it matched their own org -- the underlying tables' RLS silently scoped results to
     * the caller's real org regardless (so this was never an actual cross-tenant leak), but a
     * mismatched orgId returned a confusing empty/zero-value report instead of a clean 404. For a
     * platform admin, current_org_id() is always NULL, so every one of these reports returned empty
     * for them too, regardless of target org -- reporting_tables' RLS policies (V063) had no
     * platform-admin bypass at all until this pass.
     */
    private void authorizeOrgAccess(UserPrincipal principal, UUID targetOrgId, String reason, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginCrossOrgAccess(principal.getId(), targetOrgId, reason, resource);
            return;
        }
        if (!targetOrgId.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Organization not found: " + targetOrgId);
        }
    }
}