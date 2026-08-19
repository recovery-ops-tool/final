package com.recoverpro.server.service.impl;

import com.recoverpro.server.dto.response.AgentContextDto;
import com.recoverpro.server.enums.AssignmentStatus;
import com.recoverpro.server.enums.PtpStatus;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.AssignmentRepository;
import com.recoverpro.server.repository.CollectionRepository;
import com.recoverpro.server.repository.PtpRepository;
import com.recoverpro.server.service.AgentContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentContextServiceImpl implements AgentContextService {

    private final AssignmentRepository assignmentRepository;
    private final CollectionRepository collectionRepository;
    private final PtpRepository ptpRepository;
    private final AllocationRepository allocationRepository;

    @Override
    @Cacheable(cacheNames = "lucienContext", key = "#sessionId", sync = true)
    public AgentContextDto buildContext(String sessionId, UUID agentId, String agentFirstName) {
        log.debug("Building context for agentId={} sessionId={}", agentId, sessionId);

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        var todayAssignments = assignmentRepository.findAllByAgentAndDate(agentId, today);

        int totalAssignedToday = todayAssignments.size();
        int completedToday = (int) todayAssignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.COMPLETED)
                .count();
        int pendingToday = totalAssignedToday - completedToday;
        int overdueCases = (int) todayAssignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.PENDING)
                .count();

        int activePtps = (int) ptpRepository.countByAgentIdAndStatus(agentId, PtpStatus.PENDING);
        int brokenPtps = (int) ptpRepository.countByAgentStatusAndDateRange(
                agentId, PtpStatus.BROKEN, monthStart, today);
        long fulfilled = ptpRepository.countByAgentStatusAndDateRange(
                agentId, PtpStatus.FULFILLED, monthStart, today);
        long totalResolved = fulfilled + brokenPtps;
        BigDecimal ptpRate = totalResolved > 0
                ? BigDecimal.valueOf(fulfilled).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalResolved), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal collected = collectionRepository
                .sumCollectionByAgentBetweenDates(agentId, monthStart, today);
        int monthlyCollections = (int) collectionRepository
                .countCollectionsByAgentBetweenDates(agentId, monthStart, today);

        // Same collected/outstanding*100 formula used across ReportingServiceImpl - the agent's
        // current assigned book, not just this month's collections.
        BigDecimal outstanding = allocationRepository.sumOutstandingByAgentId(agentId);
        BigDecimal efficiency = (collected != null && outstanding != null
                && outstanding.compareTo(BigDecimal.ZERO) > 0)
                ? collected.multiply(BigDecimal.valueOf(100)).divide(outstanding, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        AgentContextDto ctx = AgentContextDto.builder()
                .agentFirstName(agentFirstName)
                .totalAssignedToday(totalAssignedToday)
                .completedToday(completedToday)
                .pendingToday(pendingToday)
                .overdueCases(overdueCases)
                .activePtps(activePtps)
                .brokenPtps(brokenPtps)
                .collectionEfficiencyPercent(efficiency)
                .ptpFulfillmentRatePercent(ptpRate)
                .totalCollectionsThisMonth(monthlyCollections)
                .build();

        log.info("Context built for agentId={}: assigned={}, completed={}, activePtps={}, efficiency={}%",
                agentId, totalAssignedToday, completedToday, activePtps, efficiency);
        return ctx;
    }
}
