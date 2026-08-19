package com.recoverpro.server.controller;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.ExportFormat;
import com.recoverpro.server.enums.ReportType;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.ExportService;
import com.recoverpro.server.util.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 35.4.b: "exports move bulk PII out of the system -- treat them accordingly." The
 * download endpoint (unlike report generation, ReportingController) had no rate limit at all
 * before this task.
 */
@ExtendWith(MockitoExtension.class)
class ExportControllerTest {

    @Mock private ExportService exportService;
    @Mock private OrgIsolationGuard orgIsolationGuard;
    @Mock private RateLimiter rateLimiter;

    private AppProperties appProperties;
    private UUID orgId;
    private UUID jobId;
    private UserPrincipal principal;

    private ExportController controller() {
        return new ExportController(exportService, orgIsolationGuard, rateLimiter, appProperties);
    }

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        orgId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        principal = mock(UserPrincipal.class);
        lenient().doReturn(UUID.randomUUID()).when(principal).getId();
        lenient().when(orgIsolationGuard.belongsToOrg(orgId)).thenReturn(true);
    }

    @Test
    void downloadReport_rateLimited_rejectsBeforeCheckingOrgOrFetching() {
        when(rateLimiter.isAllowed(anyString(), any(Integer.class), any(Integer.class))).thenReturn(false);
        when(rateLimiter.getRetryAfterSeconds(anyString())).thenReturn(42L);

        assertThatThrownBy(() -> controller().downloadReport(jobId, orgId, principal))
                .isInstanceOf(RateLimitExceededException.class);

        verify(orgIsolationGuard, never()).belongsToOrg(any());
        verify(exportService, never()).exportReport(any(), any());
    }

    @Test
    void downloadReport_withinLimit_proceedsToExport() {
        when(rateLimiter.isAllowed(anyString(), any(Integer.class), any(Integer.class))).thenReturn(true);
        ReportJob job = ReportJob.builder()
                .id(jobId).organizationId(orgId)
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.EXCEL)
                .fileName("report.xlsx")
                .build();
        when(exportService.findReportJob(jobId, orgId)).thenReturn(job);
        Resource resource = new ByteArrayResource(new byte[]{1, 2, 3});
        when(exportService.exportReport(jobId, orgId)).thenReturn(resource);

        var response = controller().downloadReport(jobId, orgId, principal);

        assertThat(response.getBody()).isSameAs(resource);
        verify(orgIsolationGuard).belongsToOrg(orgId);
    }
}
