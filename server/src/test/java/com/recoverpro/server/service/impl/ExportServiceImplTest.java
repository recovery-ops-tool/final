package com.recoverpro.server.service.impl;

import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.ExportFormat;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.enums.ReportType;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 35.4.b: a downloaded report is a bulk PII export. Before this task, the only audit
 * trail was the pre-existing, INFO-severity REPORT_EXPORTED with no filter information. Now uses
 * DATA_EXPORTED (HIGH severity, matching the task's own instruction) and carries the original
 * ReportRequest ("filters used") already stored on the job row.
 */
@ExtendWith(MockitoExtension.class)
class ExportServiceImplTest {

    @Mock private ReportJobRepository reportJobRepository;
    @Mock private AuditService auditService;

    private ExportServiceImpl service;
    private Path tempFile;

    private ExportServiceImpl newService() {
        return new ExportServiceImpl(reportJobRepository, auditService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempFile != null) Files.deleteIfExists(tempFile);
    }

    @Test
    void exportReport_audits_DATA_EXPORTED_withFiltersFromJobParameters() throws Exception {
        service = newService();
        UUID jobId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        tempFile = Files.createTempFile("export-test", ".xlsx");
        Files.write(tempFile, new byte[]{1, 2, 3});

        String filtersJson = "{\"organizationId\":\"" + orgId + "\",\"reportType\":\"COLLECTION_EFFICIENCY\"}";
        ReportJob job = ReportJob.builder()
                .id(jobId).organizationId(orgId)
                .status(ReportStatus.COMPLETED)
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.EXCEL)
                .filePath(tempFile.toString())
                .fileName("report.xlsx")
                .parameters(filtersJson)
                .build();
        when(reportJobRepository.findByIdAndOrganizationId(jobId, orgId)).thenReturn(Optional.of(job));

        service.exportReport(jobId, orgId);

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService).record(captor.capture());
        AuditEventRequest recorded = captor.getValue();
        assertThat(recorded.getAction()).isEqualTo(AuditAction.DATA_EXPORTED);
        assertThat(recorded.getMetadata()).containsEntry("filters", filtersJson);
        assertThat(recorded.getMetadata()).containsEntry("reportType", "COLLECTION_EFFICIENCY");
    }

    @Test
    void exportReport_nullParameters_omitsFiltersKeyWithoutThrowing() throws Exception {
        service = newService();
        UUID jobId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        tempFile = Files.createTempFile("export-test", ".xlsx");
        Files.write(tempFile, new byte[]{1});

        ReportJob job = ReportJob.builder()
                .id(jobId).organizationId(orgId)
                .status(ReportStatus.COMPLETED)
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.EXCEL)
                .filePath(tempFile.toString())
                .fileName("report.xlsx")
                .parameters(null)
                .build();
        when(reportJobRepository.findByIdAndOrganizationId(jobId, orgId)).thenReturn(Optional.of(job));

        service.exportReport(jobId, orgId);

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().getMetadata()).doesNotContainKey("filters");
    }
}
