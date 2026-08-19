package com.recoverpro.server.controller;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.ExportService;
import com.recoverpro.server.util.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/export")
@RequiredArgsConstructor
@Slf4j
public class ExportController {

    private final ExportService exportService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;

    @GetMapping("/jobs/{jobId}/download")
    @PreAuthorize(Authz.LEADS)
    public ResponseEntity<Resource> downloadReport(
            @PathVariable UUID jobId,
            @RequestParam UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("Download report: jobId={}, orgId={}", jobId, orgId);

        // TASK 35.4.b: "exports move bulk PII out of the system -- treat them accordingly."
        // Report generation was already rate-limited (ReportingController); the download step
        // wasn't -- same key shape (per-caller), mirrors that endpoint's own pattern exactly.
        AppProperties.Security sec = appProperties.getSecurity();
        String rateLimitKey = "report-download:" + principal.getId();
        if (!rateLimiter.isAllowed(rateLimitKey, sec.getReportDownloadMaxAttempts(), sec.getReportDownloadWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(rateLimitKey);
            throw new RateLimitExceededException(
                    "Too many report downloads. Try again in " + retryAfter + "s.", retryAfter);
        }

        // orgId is client-supplied (MANAGER/TL are org-scoped, not just admins) -- never trust it
        // without checking the caller actually belongs to it. RLS on report_jobs is fail-closed
        // so this couldn't leak another tenant's report, but there was no app-layer check either.
        if (!orgIsolationGuard.belongsToOrg(orgId)) {
            throw new ResourceNotFoundException("Report job not found: " + jobId);
        }

        ReportJob job = exportService.findReportJob(jobId, orgId);
        Resource resource = exportService.exportReport(jobId, orgId);

        MediaType mediaType = job.getExportFormat().name().equalsIgnoreCase("PDF")
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(job.getFileName())
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }
}