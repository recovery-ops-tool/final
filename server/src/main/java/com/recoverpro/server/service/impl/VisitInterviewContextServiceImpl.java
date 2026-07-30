package com.recoverpro.server.service.impl;

import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.PtpResponse;
import com.recoverpro.server.entity.VisitLog;
import com.recoverpro.server.enums.PtpStatus;
import com.recoverpro.server.repository.VisitLogRepository;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.PtpService;
import com.recoverpro.server.service.VisitInterviewContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitInterviewContextServiceImpl implements VisitInterviewContextService {

    private static final int MAX_PRIOR_VISITS = 5;

    private final AllocationService allocationService;
    private final VisitLogRepository visitLogRepository;
    private final PtpService ptpService;

    @Override
    public String buildContextBlock(UUID allocationId) {
        AllocationResponse allocation = allocationService.getAllocationById(allocationId);

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== THIS VISIT — CASE CONTEXT ===\n");
        sb.append("Borrower: ").append(nvl(allocation.getBorrowerName())).append("\n");
        sb.append("Loan number: ").append(nvl(allocation.getLoanNumber())).append("\n");
        sb.append("Outstanding amount: ").append(allocation.getOutstandingAmount() != null
                ? allocation.getOutstandingAmount() : "unknown").append("\n");
        sb.append("Total due: ").append(allocation.getTotalDue() != null
                ? allocation.getTotalDue() : "unknown").append("\n");
        sb.append("Case status: ").append(allocation.getStatus() != null
                ? allocation.getStatus() : "unknown").append("\n");
        sb.append("Last known disposition: ").append(allocation.getLatestDisposition() != null
                ? allocation.getLatestDisposition() : "none").append("\n");
        if (Boolean.TRUE.equals(allocation.getNpaFlagged())) {
            sb.append("NPA flagged: yes — treat this as a high-priority recovery.\n");
        }

        List<VisitLog> priorVisits = visitLogRepository
                .findByAllocationIdAndIsDeletedFalse(allocationId)
                .stream()
                .sorted(Comparator.comparing(VisitLog::getVisitDate).reversed())
                .limit(MAX_PRIOR_VISITS)
                .toList();
        if (priorVisits.isEmpty()) {
            sb.append("Prior visits: none — this is the first recorded visit for this case.\n");
        } else {
            sb.append("Prior visits (most recent first):\n");
            for (VisitLog v : priorVisits) {
                sb.append("  - ").append(v.getVisitDate()).append(": disposition=")
                        .append(v.getDisp() != null ? v.getDisp() : "n/a")
                        .append(", contactability=").append(v.getContactability() != null ? v.getContactability() : "n/a")
                        .append(v.getVisitNotes() != null && !v.getVisitNotes().isBlank()
                                ? ", notes=\"" + truncate(v.getVisitNotes(), 160) + "\"" : "")
                        .append("\n");
            }
        }

        List<PtpResponse> ptps = ptpService.getPtpsByAllocationId(allocationId);
        List<PtpResponse> openPtps = ptps.stream()
                .filter(p -> p.getStatus() == PtpStatus.PENDING)
                .toList();
        long brokenCount = ptps.stream().filter(p -> p.getStatus() == PtpStatus.BROKEN).count();
        if (!openPtps.isEmpty()) {
            sb.append("Open promise-to-pay: ");
            for (PtpResponse p : openPtps) {
                sb.append(p.getPromisedAmount()).append(" due ").append(p.getPromisedDate()).append("; ");
            }
            sb.append("\n");
        }
        if (brokenCount > 0) {
            sb.append("Broken PTPs on this case so far: ").append(brokenCount)
                    .append(" — mention this if the customer offers another promise.\n");
        }
        sb.append("=== END CASE CONTEXT ===\n");
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
