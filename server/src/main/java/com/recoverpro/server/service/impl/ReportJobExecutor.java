package com.recoverpro.server.service.impl;

import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.ExportFormat;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.ExportService;
import com.recoverpro.server.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * Runs a queued {@link ReportJob} on the reporting executor pool. A separate bean (rather than a
 * method on ReportingServiceImpl called via a self-injected {@code @Lazy} proxy) so the
 * {@code @Async}/{@code @Transactional(REQUIRES_NEW)} annotations actually apply -- self-invocation
 * within one class bypasses the Spring AOP proxy (SYSTEM-PLAN SP38).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportJobExecutor {

    private final ReportJobRepository reportJobRepository;
    private final ExportService exportService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;

    @Async("reportingTaskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processJobAsync(UUID jobId, Function<ReportJob, Object> reportDataBuilder) {
        ReportJob job = reportJobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        try {
            job.setStatus(ReportStatus.GENERATING);
            job.setStartedAt(Instant.now());
            reportJobRepository.save(job);

            Object reportData = reportDataBuilder.apply(job);
            byte[] content = job.getExportFormat() == ExportFormat.EXCEL
                    ? exportService.generateExcel(reportData, job.getReportType().name())
                    : exportService.generatePdf(reportData, job.getReportType().name());

            String fileName = exportService.buildFileName(
                    job.getReportType().name(), job.getExportFormat().name(), job.getOrganizationId());
            String filePath = exportService.saveToFile(content, fileName, job.getOrganizationId());

            job.setStatus(ReportStatus.COMPLETED);
            job.setFilePath(filePath);
            job.setFileName(fileName);
            job.setFileSizeBytes((long) content.length);
            job.setCompletedAt(Instant.now());
            reportJobRepository.save(job);
            log.info("Report job completed: id={} file={}", jobId, fileName);
            auditReportGeneration(job, AuditResult.SUCCESS, null);
            recordDuration(job, "success");

            if (job.getRequestedBy() != null) {
                notificationService.create(job.getRequestedBy(), job.getOrganizationId(), NotificationType.REPORT_READY,
                        "Your report is ready: " + job.getReportType(),
                        "The " + job.getReportType() + " report (" + job.getExportFormat()
                                + ") you requested has finished generating and is ready to download.");
            }

        } catch (Exception e) {
            log.error("Report job failed: id={} error={}", jobId, e.getMessage(), e);
            job.setStatus(ReportStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            reportJobRepository.save(job);
            auditReportGeneration(job, AuditResult.FAILURE, e.getMessage());
            recordDuration(job, "failure");
        }
    }

    /** SYSTEM 12 TASK 12.2: report_generation_duration_seconds{reportType, format, outcome}. */
    private void recordDuration(ReportJob job, String outcome) {
        if (job.getStartedAt() == null || job.getCompletedAt() == null) return;
        Timer.builder("report_generation_duration_seconds")
                .tag("reportType", job.getReportType().name())
                .tag("format", job.getExportFormat().name())
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.between(job.getStartedAt(), job.getCompletedAt()));
    }

    /** Async executor thread has no SecurityContext (see AsyncConfig's task decorator), so actor
     *  and organization are read off the job itself rather than server-side session context. */
    private void auditReportGeneration(ReportJob job, AuditResult result, String reason) {
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.REPORT_GENERATED)
                .resourceType(AuditResourceType.REPORT)
                .resourceId(job.getId().toString())
                .result(result)
                .reason(reason)
                .actorUserIdOverride(job.getRequestedBy())
                .actorTypeOverride(AuditActorType.BACKGROUND_JOB)
                .organizationIdOverride(job.getOrganizationId())
                .metadata(java.util.Map.of(
                        "reportType", job.getReportType().name(),
                        "format", job.getExportFormat().name()))
                .build());
    }
}
