package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.dto.response.BankReconciliationResponse;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.Collection;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.enums.CollectionStatus;
import com.recoverpro.server.enums.PaymentMode;
import com.recoverpro.server.mapper.ReportMapper;
import com.recoverpro.server.repository.AgentPerformanceSnapshotRepository;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.CollectionRepository;
import com.recoverpro.server.repository.MonthlyLoanBookSnapshotRepository;
import com.recoverpro.server.repository.NpaRecordRepository;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.ExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingServiceImplBankReconciliationTest {

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
    private UUID allocationId;

    @BeforeEach
    void setUp() {
        service = new ReportingServiceImpl(agentSnapshotRepository, loanBookSnapshotRepository,
                reportJobRepository, reportMapper, exportService, new ObjectMapper(),
                collectionRepository, allocationRepository, npaRecordRepository, reportJobExecutor,
                entitlementService);
        orgId = UUID.randomUUID();
        allocationId = UUID.randomUUID();
        lenient().when(npaRecordRepository.countGroupedByRiskLevel(any())).thenReturn(List.of());
        lenient().when(npaRecordRepository.sumOutstandingByOrg(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void getBankReconciliation_populatesLoanNumberAndBorrowerNameFromAllocation() {
        Collection collection = Collection.builder()
                .id(UUID.randomUUID())
                .allocationId(allocationId)
                .receiptNumber("RCPT-1")
                .amount(new BigDecimal("1000"))
                .paymentMode(PaymentMode.CASH)
                .status(CollectionStatus.APPROVED)
                .collectionDate(LocalDate.now())
                .submittedBy(UUID.randomUUID())
                .build();

        Page<Collection> approvedPage = new PageImpl<>(List.of(collection));
        Page<Collection> depositedPage = new PageImpl<>(List.of());
        when(collectionRepository.findWithFilters(eq(orgId), eq(null), eq(CollectionStatus.APPROVED),
                eq(null), any(), any(), any())).thenReturn(approvedPage);
        when(collectionRepository.findWithFilters(eq(orgId), eq(null), eq(CollectionStatus.DEPOSITED),
                eq(null), any(), any(), any())).thenReturn(depositedPage);

        Organization org = new Organization();
        org.setId(orgId);
        Allocation allocation = Allocation.builder()
                .id(allocationId)
                .organization(org)
                .loanNumber("LN-9999")
                .borrowerName("John Smith")
                .build();
        when(allocationRepository.findAllById(any())).thenReturn(List.of(allocation));

        BankReconciliationResponse response = service.getBankReconciliation(
                orgId, LocalDate.now().getMonthValue(), LocalDate.now().getYear());

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getLoanNumber()).isEqualTo("LN-9999");
        assertThat(response.getRows().get(0).getBorrowerName()).isEqualTo("John Smith");
    }
}
