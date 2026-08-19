package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.ReportRequest;
import com.recoverpro.server.dto.response.*;
import com.recoverpro.server.entity.AgentPerformanceSnapshot;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.Collection;
import com.recoverpro.server.entity.MonthlyLoanBookSnapshot;
import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.CollectionStatus;
import com.recoverpro.server.enums.NpaRiskLevel;
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
import com.recoverpro.server.service.ReportingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportingServiceImpl implements ReportingService {

    private final AgentPerformanceSnapshotRepository agentSnapshotRepository;
    private final MonthlyLoanBookSnapshotRepository loanBookSnapshotRepository;
    private final ReportJobRepository reportJobRepository;
    private final ReportMapper reportMapper;
    private final ExportService exportService;
    private final ObjectMapper objectMapper;
    private final CollectionRepository collectionRepository;
    private final AllocationRepository allocationRepository;
    private final NpaRecordRepository npaRecordRepository;
    private final ReportJobExecutor reportJobExecutor;
    private final EntitlementService entitlementService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Agent Performance (from snapshots) ───────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AgentPerformanceResponse getAgentPerformance(UUID agentId, LocalDate date, UUID orgId) {
        AgentPerformanceSnapshot snapshot = agentSnapshotRepository
                .findByAgentIdAndSnapshotDate(agentId, date)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No performance snapshot for agentId=" + agentId + " date=" + date));
        return reportMapper.toAgentPerformanceResponse(snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AgentPerformanceResponse> getAgentRankings(UUID orgId, LocalDate date, Pageable pageable) {
        return agentSnapshotRepository
                .findByOrganizationIdAndSnapshotDate(orgId, date, pageable)
                .map(reportMapper::toAgentPerformanceResponse);
    }

    // ── Team Performance ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TeamPerformanceResponse getTeamPerformance(UUID orgId, LocalDate from, LocalDate to) {
        List<AgentPerformanceSnapshot> snapshots = agentSnapshotRepository.findByOrgAndDateRange(orgId, from, to);
        if (snapshots.isEmpty()) {
            return TeamPerformanceResponse.builder()
                    .organizationId(orgId).fromDate(from).toDate(to)
                    .totalAgents(0).totalAssigned(0).totalVisited(0).totalCollected(0)
                    .totalAmountCollected(BigDecimal.ZERO).totalAmountOutstanding(BigDecimal.ZERO)
                    .avgCollectionEfficiency(BigDecimal.ZERO).avgVisitCompletionRate(BigDecimal.ZERO)
                    .overallEfficiencyScore(BigDecimal.ZERO).agentBreakdown(List.of())
                    .build();
        }

        Map<UUID, List<AgentPerformanceSnapshot>> byAgent = snapshots.stream()
                .collect(Collectors.groupingBy(AgentPerformanceSnapshot::getAgentId));

        List<AgentPerformanceResponse> agentSummaries = new ArrayList<>();
        BigDecimal totalCollected = BigDecimal.ZERO, totalOutstanding = BigDecimal.ZERO;
        int totalAssigned = 0, totalVisited = 0, totalCollectedCount = 0;
        BigDecimal sumEfficiency = BigDecimal.ZERO, sumVisitRate = BigDecimal.ZERO;

        for (var entry : byAgent.entrySet()) {
            AgentPerformanceSnapshot agg = aggregateSnapshots(entry.getValue(), orgId);
            agentSummaries.add(reportMapper.toAgentPerformanceResponse(agg));
            totalCollected   = totalCollected.add(agg.getAmountCollected());
            totalOutstanding = totalOutstanding.add(agg.getAmountOutstanding());
            totalAssigned    += agg.getTotalAssigned();
            totalVisited     += agg.getTotalVisited();
            totalCollectedCount += agg.getTotalCollected();
            if (agg.getCollectionEfficiency() != null) sumEfficiency = sumEfficiency.add(agg.getCollectionEfficiency());
            if (agg.getVisitCompletionRate()   != null) sumVisitRate  = sumVisitRate.add(agg.getVisitCompletionRate());
        }

        int agentCount = byAgent.size();
        BigDecimal divisor = new BigDecimal(agentCount);
        BigDecimal avgEfficiency = agentCount > 0 ? sumEfficiency.divide(divisor, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgVisitRate  = agentCount > 0 ? sumVisitRate.divide(divisor, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal overallScore  = avgVisitRate.multiply(new BigDecimal("0.40"))
                .add(avgEfficiency.multiply(new BigDecimal("0.60")))
                .setScale(2, RoundingMode.HALF_UP);

        return TeamPerformanceResponse.builder()
                .organizationId(orgId).fromDate(from).toDate(to)
                .totalAgents(agentCount)
                .totalAssigned(totalAssigned).totalVisited(totalVisited).totalCollected(totalCollectedCount)
                .totalAmountCollected(totalCollected).totalAmountOutstanding(totalOutstanding)
                .avgCollectionEfficiency(avgEfficiency).avgVisitCompletionRate(avgVisitRate)
                .overallEfficiencyScore(overallScore)
                .agentBreakdown(agentSummaries)
                .build();
    }

    // ── Daily Visit Completion ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DailyVisitCompletionResponse getDailyVisitCompletion(UUID orgId, LocalDate date) {
        List<AgentPerformanceSnapshot> snapshots =
                agentSnapshotRepository.findByOrganizationIdAndSnapshotDateOrderByEfficiencyScoreDesc(orgId, date);

        int totalAssigned = 0, totalVisited = 0;
        List<DailyVisitCompletionResponse.AgentDailyRow> rows = new ArrayList<>();
        for (AgentPerformanceSnapshot s : snapshots) {
            totalAssigned += s.getTotalAssigned();
            totalVisited  += s.getTotalVisited();
            rows.add(DailyVisitCompletionResponse.AgentDailyRow.builder()
                    .agentId(s.getAgentId())
                    .assigned(s.getTotalAssigned())
                    .visited(s.getTotalVisited())
                    .pending(s.getTotalPending())
                    .completionRate(s.getVisitCompletionRate() != null ? s.getVisitCompletionRate() : BigDecimal.ZERO)
                    .build());
        }
        BigDecimal overallRate = totalAssigned > 0
                ? new BigDecimal(totalVisited).multiply(new BigDecimal("100"))
                        .divide(new BigDecimal(totalAssigned), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return DailyVisitCompletionResponse.builder()
                .reportDate(date).organizationId(orgId)
                .totalAgentsWorking(snapshots.size())
                .totalAssigned(totalAssigned).totalVisited(totalVisited)
                .totalPending(totalAssigned - totalVisited)
                .overallCompletionRate(overallRate).agentRows(rows)
                .build();
    }

    @Transactional(readOnly = true)
    public DailyVisitCompletionResponse getVisitCompletionRange(UUID orgId, LocalDate from, LocalDate to) {
        List<AgentPerformanceSnapshot> snapshots = agentSnapshotRepository.findByOrgAndDateRange(orgId, from, to);
        Map<UUID, List<AgentPerformanceSnapshot>> byAgent = snapshots.stream()
                .collect(Collectors.groupingBy(AgentPerformanceSnapshot::getAgentId));

        int totalAssigned = 0, totalVisited = 0;
        List<DailyVisitCompletionResponse.AgentDailyRow> rows = new ArrayList<>();
        for (var entry : byAgent.entrySet()) {
            int assigned = entry.getValue().stream().mapToInt(AgentPerformanceSnapshot::getTotalAssigned).sum();
            int visited  = entry.getValue().stream().mapToInt(AgentPerformanceSnapshot::getTotalVisited).sum();
            int pending  = entry.getValue().stream().mapToInt(AgentPerformanceSnapshot::getTotalPending).sum();
            totalAssigned += assigned;
            totalVisited  += visited;
            BigDecimal rate = assigned > 0
                    ? new BigDecimal(visited).multiply(new BigDecimal("100"))
                            .divide(new BigDecimal(assigned), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            rows.add(DailyVisitCompletionResponse.AgentDailyRow.builder()
                    .agentId(entry.getKey()).assigned(assigned).visited(visited)
                    .pending(pending).completionRate(rate).build());
        }
        rows.sort(Comparator.comparing(DailyVisitCompletionResponse.AgentDailyRow::getCompletionRate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        BigDecimal overallRate = totalAssigned > 0
                ? new BigDecimal(totalVisited).multiply(new BigDecimal("100"))
                        .divide(new BigDecimal(totalAssigned), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return DailyVisitCompletionResponse.builder()
                .reportDate(from).organizationId(orgId)
                .totalAgentsWorking(byAgent.size())
                .totalAssigned(totalAssigned).totalVisited(totalVisited)
                .totalPending(totalAssigned - totalVisited)
                .overallCompletionRate(overallRate).agentRows(rows)
                .build();
    }

    // ── Collection Efficiency ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CollectionEfficiencyResponse getCollectionEfficiency(UUID orgId, LocalDate from, LocalDate to) {
        List<AgentPerformanceSnapshot> snapshots = agentSnapshotRepository.findByOrgAndDateRange(orgId, from, to);
        Map<UUID, List<AgentPerformanceSnapshot>> byAgent = snapshots.stream()
                .collect(Collectors.groupingBy(AgentPerformanceSnapshot::getAgentId));

        BigDecimal totalOutstanding = BigDecimal.ZERO, totalCollected = BigDecimal.ZERO;
        List<CollectionEfficiencyResponse.AgentEfficiencyRow> rows = new ArrayList<>();
        AtomicInteger rank = new AtomicInteger(1);

        List<AgentPerformanceSnapshot> aggregated = byAgent.entrySet().stream()
                .map(e -> aggregateSnapshots(e.getValue(), orgId))
                .sorted(Comparator.comparing(AgentPerformanceSnapshot::getCollectionEfficiency,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        for (AgentPerformanceSnapshot agg : aggregated) {
            BigDecimal outstanding = agg.getAmountOutstanding();
            BigDecimal collected   = agg.getAmountCollected();
            totalOutstanding = totalOutstanding.add(outstanding);
            totalCollected   = totalCollected.add(collected);
            BigDecimal effPct = outstanding.compareTo(BigDecimal.ZERO) > 0
                    ? collected.multiply(new BigDecimal("100")).divide(outstanding, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            rows.add(CollectionEfficiencyResponse.AgentEfficiencyRow.builder()
                    .agentId(agg.getAgentId())
                    .amountOutstanding(outstanding).amountCollected(collected)
                    .efficiencyPct(effPct).recoveryRatePct(effPct)
                    .rank(rank.getAndIncrement())
                    .build());
        }
        BigDecimal overallEff = totalOutstanding.compareTo(BigDecimal.ZERO) > 0
                ? totalCollected.multiply(new BigDecimal("100")).divide(totalOutstanding, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return CollectionEfficiencyResponse.builder()
                .organizationId(orgId).fromDate(from).toDate(to)
                .totalOutstanding(totalOutstanding).totalCollected(totalCollected)
                .collectionEfficiencyPct(overallEff).recoveryRatePct(overallEff)
                .agentBreakdown(rows)
                .build();
    }

    // ── Reassignment Frequency ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ReassignmentFrequencyResponse getReassignmentFrequency(UUID orgId, LocalDate from, LocalDate to) {
        List<AgentPerformanceSnapshot> snapshots = agentSnapshotRepository.findByOrgAndDateRange(orgId, from, to);
        Map<UUID, List<AgentPerformanceSnapshot>> byAgent = snapshots.stream()
                .collect(Collectors.groupingBy(AgentPerformanceSnapshot::getAgentId));

        int totalReassignments = 0;
        List<ReassignmentFrequencyResponse.AgentReassignmentRow> rows = new ArrayList<>();
        for (var entry : byAgent.entrySet()) {
            int out = entry.getValue().stream().mapToInt(AgentPerformanceSnapshot::getTotalReassignedOut).sum();
            int in  = entry.getValue().stream().mapToInt(AgentPerformanceSnapshot::getTotalReassignedIn).sum();
            totalReassignments += out;
            int days = entry.getValue().size();
            double rate = days > 0 ? (double) (out + in) / days : 0.0;
            rows.add(ReassignmentFrequencyResponse.AgentReassignmentRow.builder()
                    .agentId(entry.getKey())
                    .reassignedOut(out).reassignedIn(in).netReassignments(in - out)
                    .reassignmentRate(Math.round(rate * 100.0) / 100.0)
                    .build());
        }
        rows.sort(Comparator.comparingInt(ReassignmentFrequencyResponse.AgentReassignmentRow::getReassignedOut).reversed());
        double avg = byAgent.isEmpty() ? 0.0 : (double) totalReassignments / byAgent.size();
        return ReassignmentFrequencyResponse.builder()
                .organizationId(orgId).fromDate(from).toDate(to)
                .totalReassignments(totalReassignments)
                .avgReassignmentsPerAgent(Math.round(avg * 100.0) / 100.0)
                .agentBreakdown(rows)
                .build();
    }

    // ── Monthly Loan Book ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<MonthlyLoanBookResponse> getMonthlyLoanBookHistory(UUID orgId, Pageable pageable) {
        return loanBookSnapshotRepository
                .findByOrganizationIdOrderBySnapshotMonthDesc(orgId, pageable)
                .map(reportMapper::toMonthlyLoanBookResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public MonthlyLoanBookResponse getMonthlyLoanBook(UUID orgId, int month, int year) {
        MonthlyLoanBookSnapshot snapshot = loanBookSnapshotRepository
                .findByOrganizationIdAndSnapshotMonth(orgId, LocalDate.of(year, month, 1))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No loan book snapshot for org=" + orgId + " month=" + month + "/" + year));
        return reportMapper.toMonthlyLoanBookResponse(snapshot);
    }

    // ── Bank Reconciliation ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BankReconciliationResponse getBankReconciliation(UUID orgId, int month, int year) {
        log.info("Generating bank reconciliation for orgId={}, month={}/{}", orgId, month, year);
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate   = startDate.plusMonths(1).minusDays(1);

        List<Collection> collections = collectionRepository.findWithFilters(
                orgId, null, CollectionStatus.APPROVED, null, startDate, endDate, Pageable.unpaged()).getContent();
        List<Collection> depositedCollections = collectionRepository.findWithFilters(
                orgId, null, CollectionStatus.DEPOSITED, null, startDate, endDate, Pageable.unpaged()).getContent();

        BigDecimal totalCash = BigDecimal.ZERO, totalUpi = BigDecimal.ZERO,
                   totalCheque = BigDecimal.ZERO, totalNeft = BigDecimal.ZERO, totalRtgs = BigDecimal.ZERO;
        for (Collection c : collections) {
            switch (c.getPaymentMode()) {
                case CASH   -> totalCash   = totalCash.add(c.getAmount());
                case UPI    -> totalUpi    = totalUpi.add(c.getAmount());
                case CHEQUE -> totalCheque = totalCheque.add(c.getAmount());
                case NEFT   -> totalNeft   = totalNeft.add(c.getAmount());
                case RTGS   -> totalRtgs   = totalRtgs.add(c.getAmount());
            }
        }
        BigDecimal grandTotal = totalCash.add(totalUpi).add(totalCheque).add(totalNeft).add(totalRtgs);
        BigDecimal depositedTotal = depositedCollections.stream()
                .map(Collection::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<UUID, Allocation> allocationsById = allocationRepository
                .findAllById(collections.stream().map(Collection::getAllocationId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Allocation::getId, a -> a));

        List<BankReconciliationResponse.ReconciliationRow> rows = collections.stream()
                .map(c -> {
                    Allocation alloc = allocationsById.get(c.getAllocationId());
                    return BankReconciliationResponse.ReconciliationRow.builder()
                        .collectionId(c.getId())
                        .receiptNumber(c.getReceiptNumber())
                        .loanNumber(alloc != null ? alloc.getLoanNumber() : "")
                        .borrowerName(alloc != null ? alloc.getBorrowerName() : "")
                        .amount(c.getAmount())
                        .paymentMode(c.getPaymentMode().name())
                        .status(c.getStatus().name())
                        .collectionDate(c.getCollectionDate())
                        .depositedDate(c.getDepositedAt() != null
                                ? c.getDepositedAt().atZone(IST).toLocalDate()
                                : null)
                        .agentId(c.getSubmittedBy())
                        .build();
                })
                .collect(Collectors.toList());

        Map<NpaRiskLevel, Integer> npaBreakdown = new EnumMap<>(NpaRiskLevel.class);
        for (Object[] row : npaRecordRepository.countGroupedByRiskLevel(orgId)) {
            npaBreakdown.put((NpaRiskLevel) row[0], ((Number) row[1]).intValue());
        }
        BigDecimal npaTotal = npaRecordRepository.sumOutstandingByOrg(orgId);

        return BankReconciliationResponse.builder()
                .organizationId(orgId).month(month).year(year)
                .generatedOn(LocalDate.now())
                .totalCollectedCash(totalCash).totalCollectedUpi(totalUpi)
                .totalCollectedCheque(totalCheque).totalCollectedNeft(totalNeft).totalCollectedRtgs(totalRtgs)
                .grandTotalCollected(grandTotal)
                .totalApprovedTransactions(collections.size())
                .totalDepositedTransactions(depositedCollections.size())
                .totalPendingDeposit(collections.size() - depositedCollections.size())
                .totalDepositedAmount(depositedTotal)
                .totalPendingDepositAmount(grandTotal.subtract(depositedTotal))
                .npaBreakdown(npaBreakdown)
                .totalNpaAmount(npaTotal != null ? npaTotal : BigDecimal.ZERO)
                .rows(rows)
                .build();
    }

    // ── Report Job Queue ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public ReportJobResponse enqueueReport(ReportRequest request, UUID requestedBy) {
        // TASK 20.4: cheapest possible rejection first, before the existing-job existence check
        // or any DB write.
        if (!entitlementService.canGenerateReport(request.getOrganizationId())) {
            throw new BusinessException(
                    "Monthly report generation limit reached for this organization's plan. Upgrade your plan or contact support.");
        }
        List<ReportStatus> activeStatuses = List.of(ReportStatus.QUEUED, ReportStatus.GENERATING);
        if (reportJobRepository.existsByRequestedByAndStatusIn(requestedBy, activeStatuses)) {
            throw new BusinessException(
                    "A report is already QUEUED or GENERATING for your account. Wait for it to complete before requesting a new one.");
        }
        if (request.getFromDate() != null && request.getToDate() != null) {
            long days = ChronoUnit.DAYS.between(request.getFromDate(), request.getToDate());
            if (days > 365) throw new BusinessException("Report date range cannot exceed 365 days (requested: " + days + " days).");
            if (days < 0)   throw new BusinessException("fromDate must be before toDate.");
        }
        ReportJob job = ReportJob.builder()
                .organizationId(request.getOrganizationId())
                .reportType(request.getReportType())
                .exportFormat(request.getExportFormat())
                .status(ReportStatus.QUEUED)
                .parameters(serializeParams(request))
                .requestedBy(requestedBy)
                .isScheduled(false)
                .build();
        ReportJob saved = reportJobRepository.save(job);
        log.info("Report job enqueued: id={} type={} format={}", saved.getId(), saved.getReportType(), saved.getExportFormat());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reportJobExecutor.processJobAsync(saved.getId(), ReportingServiceImpl.this::buildReportData);
            }
        });
        return reportMapper.toJobResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportJobResponse getJobStatus(UUID jobId, UUID orgId) {
        ReportJob job = reportJobRepository.findByIdAndOrganizationId(jobId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Report job not found: " + jobId));
        return reportMapper.toJobResponse(job);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReportJobResponse> getJobs(UUID orgId, ReportType type, ReportStatus status, Pageable pageable) {
        return reportJobRepository.findWithFilters(orgId, type, status, pageable)
                .map(reportMapper::toJobResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Object buildReportData(ReportJob job) {
        try {
            ReportRequest params = job.getParameters() != null
                    ? objectMapper.readValue(job.getParameters(), ReportRequest.class)
                    : new ReportRequest();
            LocalDate from  = params.getFromDate() != null ? params.getFromDate() : LocalDate.now().minusMonths(1);
            LocalDate to    = params.getToDate()   != null ? params.getToDate()   : LocalDate.now();
            int month = params.getMonth() != null ? params.getMonth() : to.getMonthValue();
            int year  = params.getYear()  != null ? params.getYear()  : to.getYear();

            return switch (job.getReportType()) {
                case AGENT_PERFORMANCE, TEAM_PERFORMANCE -> getTeamPerformance(job.getOrganizationId(), from, to);
                case DAILY_VISIT_COMPLETION, MONTHLY_VISIT_COMPLETION ->
                        getVisitCompletionRange(job.getOrganizationId(), from, to);
                case COLLECTION_EFFICIENCY  -> getCollectionEfficiency(job.getOrganizationId(), from, to);
                case REASSIGNMENT_FREQUENCY -> getReassignmentFrequency(job.getOrganizationId(), from, to);
                case MONTHLY_LOAN_BOOK_SNAPSHOT -> getMonthlyLoanBook(job.getOrganizationId(), month, year);
                case BANK_RECONCILIATION    -> getBankReconciliation(job.getOrganizationId(), month, year);
                default -> Map.of("reportType", job.getReportType(), "orgId", job.getOrganizationId());
            };
        } catch (Exception e) {
            log.warn("Failed to deserialize report params for job {}: {}", job.getId(), e.getMessage());
            return Map.of("reportType", job.getReportType().name());
        }
    }

    private AgentPerformanceSnapshot aggregateSnapshots(List<AgentPerformanceSnapshot> list, UUID orgId) {
        UUID agentId = list.get(0).getAgentId();
        int assigned = 0, visited = 0, collected = 0, pending = 0, reOut = 0, reIn = 0;
        BigDecimal amtCollected = BigDecimal.ZERO, amtOutstanding = BigDecimal.ZERO;
        for (AgentPerformanceSnapshot s : list) {
            assigned     += s.getTotalAssigned();
            visited      += s.getTotalVisited();
            collected    += s.getTotalCollected();
            pending      += s.getTotalPending();
            reOut        += s.getTotalReassignedOut();
            reIn         += s.getTotalReassignedIn();
            amtCollected  = amtCollected.add(s.getAmountCollected());
            amtOutstanding = amtOutstanding.add(s.getAmountOutstanding());
        }
        BigDecimal visitRate = assigned > 0
                ? new BigDecimal(visited).multiply(new BigDecimal("100"))
                        .divide(new BigDecimal(assigned), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal collEff = amtOutstanding.compareTo(BigDecimal.ZERO) > 0
                ? amtCollected.multiply(new BigDecimal("100")).divide(amtOutstanding, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal score = visitRate.multiply(new BigDecimal("0.40"))
                .add(collEff.multiply(new BigDecimal("0.60"))).setScale(2, RoundingMode.HALF_UP);
        return AgentPerformanceSnapshot.builder()
                .agentId(agentId).organizationId(orgId)
                .snapshotDate(list.get(list.size() - 1).getSnapshotDate())
                .totalAssigned(assigned).totalVisited(visited).totalCollected(collected)
                .totalPending(pending).totalReassignedOut(reOut).totalReassignedIn(reIn)
                .amountCollected(amtCollected).amountOutstanding(amtOutstanding)
                .visitCompletionRate(visitRate).collectionEfficiency(collEff).efficiencyScore(score)
                .build();
    }

    private String serializeParams(ReportRequest request) {
        try { return objectMapper.writeValueAsString(request); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
