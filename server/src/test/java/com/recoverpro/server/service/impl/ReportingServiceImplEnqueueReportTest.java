package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.dto.request.ReportRequest;
import com.recoverpro.server.dto.response.ReportJobResponse;
import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.ExportFormat;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.enums.ReportType;
import com.recoverpro.server.mapper.ReportMapper;
import com.recoverpro.server.repository.AgentPerformanceSnapshotRepository;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.CollectionRepository;
import com.recoverpro.server.repository.MonthlyLoanBookSnapshotRepository;
import com.recoverpro.server.repository.NpaRecordRepository;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.ExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the report-generation permanent-QUEUED hang: enqueueReport is
 * @Transactional and used to fire the @Async processJobAsync call synchronously from inside that
 * transaction. The async thread runs in its own REQUIRES_NEW transaction on a separate connection,
 * so under READ COMMITTED isolation it frequently could not see the just-inserted row yet --
 * findById returned empty and the method returned silently, leaving the job stuck in QUEUED
 * forever. Fix: defer the processJobAsync call to a TransactionSynchronization#afterCommit
 * callback, mirroring the existing pattern in RagDocumentServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class ReportingServiceImplEnqueueReportTest {

    @Mock private AgentPerformanceSnapshotRepository agentSnapshotRepository;
    @Mock private MonthlyLoanBookSnapshotRepository loanBookSnapshotRepository;
    @Mock private ReportJobRepository reportJobRepository;
    @Mock private ReportMapper reportMapper;
    @Mock private ExportService exportService;
    @Mock private CollectionRepository collectionRepository;
    @Mock private AllocationRepository allocationRepository;
    @Mock private NpaRecordRepository npaRecordRepository;
    @Mock private ReportJobExecutor reportJobExecutor;
    @Mock private EntitlementService entitlementService;

    private ReportingServiceImpl service;
    private UUID orgId;
    private UUID requestedBy;

    @BeforeEach
    void setUp() {
        service = new ReportingServiceImpl(agentSnapshotRepository, loanBookSnapshotRepository,
                reportJobRepository, reportMapper, exportService, new ObjectMapper(),
                collectionRepository, allocationRepository, npaRecordRepository, reportJobExecutor,
                entitlementService);
        orgId = UUID.randomUUID();
        requestedBy = UUID.randomUUID();
        lenient().when(entitlementService.canGenerateReport(any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void enqueueReport_doesNotStartProcessingBeforeTransactionCommits() {
        UUID jobId = UUID.randomUUID();
        when(reportJobRepository.existsByRequestedByAndStatusIn(any(), any())).thenReturn(false);
        when(reportJobRepository.save(any(ReportJob.class))).thenAnswer(inv -> {
            ReportJob job = inv.getArgument(0);
            if (job.getId() == null) job.setId(jobId);
            return job;
        });
        when(reportMapper.toJobResponse(any())).thenReturn(ReportJobResponse.builder().id(jobId).build());

        ReportRequest request = ReportRequest.builder()
                .organizationId(orgId)
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.EXCEL)
                .build();

        ReportJobResponse response = service.enqueueReport(request, requestedBy);

        assertThat(response.getId()).isEqualTo(jobId);
        verify(reportJobExecutor, never()).processJobAsync(any(), any());

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(reportJobExecutor).processJobAsync(eq(jobId), any());
    }

    /**
     * SYSTEM-PLAN 20.4: the entitlement check runs before the existing-job existence check or any
     * DB write -- a plan-limit rejection should be the cheapest possible failure. Asserts the job
     * is never persisted once the org's monthly report-generation cap is exhausted.
     */
    @Test
    void enqueueReport_atMonthlyLimit_rejectsBeforeAnyWrite() {
        when(entitlementService.canGenerateReport(orgId)).thenReturn(false);

        ReportRequest request = ReportRequest.builder()
                .organizationId(orgId)
                .reportType(ReportType.COLLECTION_EFFICIENCY)
                .exportFormat(ExportFormat.EXCEL)
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.enqueueReport(request, requestedBy))
                .isInstanceOf(com.recoverpro.server.common.exception.BusinessException.class)
                .hasMessageContaining("report generation limit");

        verify(reportJobRepository, never()).save(any());
        verify(reportJobRepository, never()).existsByRequestedByAndStatusIn(any(), any());
    }
}
