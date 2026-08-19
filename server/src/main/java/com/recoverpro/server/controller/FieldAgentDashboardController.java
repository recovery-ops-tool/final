package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.response.FieldAgentDashboardResponse;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.DailyVisitList;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.entity.VisitLog;
import com.recoverpro.server.enums.PtpStatus;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.CollectionRepository;
import com.recoverpro.server.repository.DailyVisitListRepository;
import com.recoverpro.server.repository.PtpRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.repository.VisitLogRepository;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class FieldAgentDashboardController {

    private static final String FO_OR_LEADS = Authz.LEADS_AND_FO;

    private final UserRepository userRepository;
    private final DailyVisitListRepository dispatchRepo;
    private final AllocationRepository allocationRepo;
    private final VisitLogRepository visitLogRepo;
    private final CollectionRepository collectionRepo;
    private final PtpRepository ptpRepo;

    @GetMapping("/field-agent/{agentId}")
    @PreAuthorize(FO_OR_LEADS)
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<FieldAgentDashboardResponse>> fieldAgentDashboard(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID agentId) {

        boolean isOnlyFo = isOnlyFieldOfficer(principal);
        if (isOnlyFo && !principal.getId().equals(agentId)) {
            throw new ResourceNotFoundException("Dashboard not found");
        }

        User agent = userRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found: " + agentId));

        if (!isPlatformAdmin(principal)) {
            UUID callerOrg = principal.getOrganizationId();
            if (callerOrg == null || !callerOrg.equals(agent.getOrganizationId())) {
                throw new ResourceNotFoundException("Dashboard not found");
            }
        }

        LocalDate today = LocalDate.now();

        List<DailyVisitList> dispatchRows = dispatchRepo
                .findByAgentUserIdAndDispatchDateOrderBySequenceOrderAsc(agentId, today);

        Set<UUID> allocationIds = dispatchRows.stream()
                .map(DailyVisitList::getAllocationId)
                .collect(Collectors.toSet());

        Map<UUID, Allocation> allocationsById = allocationIds.isEmpty()
                ? Map.of()
                : allocationRepo.findAllById(allocationIds).stream()
                        .collect(Collectors.toMap(Allocation::getId, a -> a));

        Set<UUID> completedAllocationIds = visitLogRepo
                .findByAgentIdAndVisitDateAndIsDeletedFalse(agentId, today)
                .stream()
                .map(VisitLog::getAllocationId)
                .collect(Collectors.toSet());

        List<FieldAgentDashboardResponse.TodayAssignmentEntry> assignments = dispatchRows.stream()
                .map(row -> {
                    Allocation alloc = allocationsById.get(row.getAllocationId());
                    if (alloc == null || Boolean.TRUE.equals(alloc.getIsDeleted())) return null;

                    boolean done = completedAllocationIds.contains(row.getAllocationId());
                    String priorityLevel = Boolean.TRUE.equals(alloc.getNpaFlagged()) ? "HIGH" : "NORMAL";

                    return FieldAgentDashboardResponse.TodayAssignmentEntry.builder()
                            .assignmentId(row.getId())
                            .allocationId(alloc.getId())
                            .loanNumber(alloc.getLoanNumber())
                            .borrowerName(alloc.getBorrowerName())
                            .status(done ? "COMPLETED" : "PENDING")
                            .priorityLevel(priorityLevel)
                            .sequenceOrder(row.getSequenceOrder())
                            .npaFlagged(Boolean.TRUE.equals(alloc.getNpaFlagged()))
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int total = assignments.size();
        int completed = (int) assignments.stream().filter(a -> "COMPLETED".equals(a.getStatus())).count();
        int pending = total - completed;
        double completionRate = total > 0 ? (completed * 100.0 / total) : 0.0;

        var collectionsToday = collectionRepo.findByAgentAndDate(agentId, today);
        double collectedAmountToday = collectionsToday.stream()
                .mapToDouble(c -> c.getAmount() != null ? c.getAmount().doubleValue() : 0.0)
                .sum();
        long pendingPtps = ptpRepo.countByAgentIdAndStatus(agentId, PtpStatus.PENDING);
        long ptpsDueToday = ptpRepo.countByAgentIdAndPromisedDate(agentId, today);

        FieldAgentDashboardResponse response = FieldAgentDashboardResponse.builder()
                .agentId(agent.getId())
                .agentFirstName(agent.getFirstName())
                .agentLastName(agent.getLastName())
                .today(today.toString())
                .todayTotalCases(total)
                .todayCompletedCases(completed)
                .todayPendingCases(pending)
                .todayRescheduledCases(0)
                .todayCompletionRate(completionRate)
                .collectedAmountToday(collectedAmountToday)
                .collectionsSubmittedToday(collectionsToday.size())
                .pendingPtps((int) pendingPtps)
                .ptpsDueToday((int) ptpsDueToday)
                .todayAssignments(assignments)
                .build();

        log.debug("Field agent dashboard: agentId={} total={} completed={}", agentId, total, completed);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private boolean isOnlyFieldOfficer(UserPrincipal p) {
        boolean hasFo = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_FO".equals(a.getAuthority()));
        boolean hasLead = p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TL".equals(a.getAuthority())
                        || "ROLE_MANAGER".equals(a.getAuthority())
                        || "ROLE_ORG_ADMIN".equals(a.getAuthority())
                        || "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        return hasFo && !hasLead;
    }
}
