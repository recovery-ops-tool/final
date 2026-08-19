package com.recoverpro.server.service.impl;

import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.ExportFormat;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.enums.ReportType;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.ExportService;
import com.recoverpro.server.service.NotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportJobExecutorTest {

    @Mock private ReportJobRepository reportJobRepository;
    @Mock private ExportService exportService;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;

    private ReportJobExecutor executor;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        executor = new ReportJobExecutor(
                reportJobRepository, exportService, notificationService, auditService, meterRegistry);
    }

    @Test
    void processJobAsync_success_marksCompletedWithFilePath() {
        UUID jobId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        ReportJob job = ReportJob.builder()
                .id(jobId).organizationId(orgId)
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.PDF)
                .status(ReportStatus.QUEUED)
                .build();
        when(reportJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(exportService.generatePdf(any(), any())).thenReturn("pdf-bytes".getBytes());
        when(exportService.buildFileName(any(), any(), any())).thenReturn("report.pdf");
        when(exportService.saveToFile(any(), any(), any())).thenReturn("/reports/report.pdf");

        Function<ReportJob, Object> dataBuilder = j -> "report-data";
        executor.processJobAsync(jobId, dataBuilder);

        assertThat(job.getStatus()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(job.getFilePath()).isEqualTo("/reports/report.pdf");
        assertThat(job.getCompletedAt()).isNotNull();

        ArgumentCaptor<ReportJob> captor = ArgumentCaptor.forClass(ReportJob.class);
        verify(reportJobRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());

        // SYSTEM 12 TASK 12.2: report_generation_duration_seconds appears in the registry.
        assertThat(meterRegistry.get("report_generation_duration_seconds")
                .tag("reportType", "COLLECTION_EFFICIENCY").tag("format", "PDF").tag("outcome", "success")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void processJobAsync_dataBuilderThrows_marksFailed() {
        UUID jobId = UUID.randomUUID();
        ReportJob job = ReportJob.builder()
                .id(jobId).organizationId(UUID.randomUUID())
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.PDF)
                .status(ReportStatus.QUEUED)
                .build();
        when(reportJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Function<ReportJob, Object> failingBuilder = j -> { throw new RuntimeException("boom"); };
        executor.processJobAsync(jobId, failingBuilder);

        assertThat(job.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void processJobAsync_jobNotFound_isNoOp() {
        UUID jobId = UUID.randomUUID();
        when(reportJobRepository.findById(jobId)).thenReturn(Optional.empty());

        executor.processJobAsync(jobId, j -> "data");

        verify(reportJobRepository, org.mockito.Mockito.never()).save(any());
    }
}
