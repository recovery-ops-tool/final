package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.AllocationFilterRequest;
import com.recoverpro.server.dto.request.BulkAssignToFoRequest;
import com.recoverpro.server.dto.request.UpdateAllocationDispositionRequest;
import com.recoverpro.server.dto.request.UpdateAllocationStatusRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.AllocationAuditLog;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AllocationStatus;
import com.recoverpro.server.enums.Disp;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.mapper.AllocationMapper;
import com.recoverpro.server.repository.AllocationAuditLogRepository;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.BorrowerRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.service.ActiveDatasetResolver;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationServiceImpl implements AllocationService {

    private final AllocationRepository allocationRepository;
    private final AllocationAuditLogRepository allocationAuditLogRepository;
    private final AllocationMapper allocationMapper;
    private final UserRepository userRepository;
    private final BorrowerRepository borrowerRepository;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ActiveDatasetResolver activeDatasetResolver;
    private final NotificationService notificationService;
    private final com.recoverpro.server.security.encryption.LookupHashService lookupHashService;

    /**
     * TASK 26.2: {@code sortBy} used to flow straight from the request into {@code Sort.by()} with
     * no validation at all -- a caller could request {@code sortBy=borrowerName} (an
     * {@code EncryptedStringConverter} field, sorting on ciphertext bytes -- meaningless but still
     * against the rule 26.2.b states directly) or any nonexistent/internal property, which
     * {@code findAllWithFilters}'s JPQL {@code ORDER BY} would then fail to resolve at runtime
     * (500, not the 400 ACCEPTANCE requires). Only plain, non-encrypted, genuinely list-relevant
     * columns are allowed.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "status", "status",
            "outstandingAmount", "outstandingAmount",
            "totalDue", "totalDue",
            "loanNumber", "loanNumber",
            "assignedAt", "assignedAt");

    private static final UUID NO_ACTIVE_DATASET =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final List<String> EMI_KEYS = List.of(
            "emi", "EMI", "Emi", "emi_amount", "emiAmount", "EMI_AMOUNT",
            "monthly_emi", "monthlyEmi", "Monthly EMI", "Monthly_EMI",
            "monthly_installment", "monthlyInstallment", "Monthly Installment",
            "installment", "Installment", "INSTALLMENT");

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AllocationResponse> getAllocations(AllocationFilterRequest filterRequest) {
        Sort sort = buildSort(filterRequest.getSortBy(), filterRequest.getSortDirection());
        Pageable pageable = PageRequest.of(
                filterRequest.getPage(),
                filterRequest.getSize() > 0 ? filterRequest.getSize() : 20,
                sort);

        String statusStr = filterRequest.getStatus() != null ? filterRequest.getStatus().name() : null;

        String normalizedSearch = StringUtils.hasText(filterRequest.getSearchTerm())
                ? filterRequest.getSearchTerm().trim() : null;
        String searchTermHash = (normalizedSearch != null && normalizedSearch.length() >= 2)
                ? lookupHashService.hash(normalizedSearch) : null;
        String searchPattern = normalizedSearch != null
                ? normalizedSearch.toLowerCase(Locale.ROOT) + "%" : null;

        UUID fileUploadId = filterRequest.getFileUploadId();
        if (fileUploadId == null) {
            fileUploadId = activeDatasetResolver.activeFileId(filterRequest.getOrganizationId())
                    .orElse(NO_ACTIVE_DATASET);
        }

        Page<Allocation> page = allocationRepository.findAllWithFilters(
                filterRequest.getOrganizationId(), statusStr, fileUploadId,
                filterRequest.getAssignedToUserId(), searchPattern, searchTermHash, pageable);

        Page<AllocationResponse> responsePage = page.map(allocationMapper::toResponse);
        List<AllocationResponse> content = new ArrayList<>(responsePage.getContent());
        enrichListView(content);
        return PagedResponse.from(responsePage);
    }

    @Override
    @Transactional(readOnly = true)
    public AllocationResponse getAllocationById(UUID id) {
        Allocation allocation = allocationRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found: " + id));
        if (!orgIsolationGuard.belongsToOrg(allocation.getOrganization().getId())) {
            throw new ResourceNotFoundException("Allocation not found: " + id);
        }
        AllocationResponse resp = allocationMapper.toResponse(allocation);
        enrichListView(List.of(resp));
        return resp;
    }

    @Override
    @Transactional
    public AllocationResponse updateAllocationStatus(UUID id, UpdateAllocationStatusRequest request, UUID userId) {
        Allocation allocation = allocationRepository.findByIdAndIsDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found: " + id));
        if (!orgIsolationGuard.belongsToOrg(allocation.getOrganization().getId())) {
            throw new ResourceNotFoundException("Allocation not found: " + id);
        }

        AllocationStatus previousStatus = allocation.getStatus();
        allocation.setStatus(request.getStatus());

        if (previousStatus != request.getStatus()) {
            allocationAuditLogRepository.save(AllocationAuditLog.builder()
                    .allocationId(id)
                    .action("STATUS_CHANGED")
                    .performedBy(userId)
                    .previousValue(previousStatus == null ? null : previousStatus.name())
                    .newValue(request.getStatus() == null ? null : request.getStatus().name())
                    .build());
        }

        if (request.getStatus() == AllocationStatus.ASSIGNED && request.getAssignedToUserId() != null) {
            UUID allocationOrgId = allocation.getOrganization().getId();
            User assignee = userRepository.findById(request.getAssignedToUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getAssignedToUserId()));
            if (!allocationOrgId.equals(assignee.getOrganizationId())) {
                throw new BusinessException("Cannot assign to a user outside the allocation's organization");
            }
            allocation.setAssignedToUserId(request.getAssignedToUserId());
            allocation.setAssignedAt(Instant.now());
            notificationService.create(assignee.getId(), allocationOrgId, NotificationType.FO_CASE_ASSIGNED,
                    "New case assigned: " + allocation.getLoanNumber(),
                    "You've been assigned loan " + allocation.getLoanNumber() + ".");
        } else if (request.getStatus() == AllocationStatus.UNASSIGNED) {
            allocation.setAssignedToUserId(null);
            allocation.setAssignedAt(null);
        }

        return allocationMapper.toResponse(allocationRepository.save(allocation));
    }

    @Override
    @Transactional
    public AllocationResponse updateAllocationDisposition(UUID id, UpdateAllocationDispositionRequest request, UUID userId) {
        Allocation allocation = allocationRepository.findByIdAndIsDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found: " + id));
        if (!orgIsolationGuard.belongsToOrg(allocation.getOrganization().getId())) {
            throw new ResourceNotFoundException("Allocation not found: " + id);
        }

        Disp previousDisp = allocation.getLatestDisposition();
        allocation.setLatestDisposition(request.getDisposition());

        if (previousDisp != request.getDisposition()) {
            allocationAuditLogRepository.save(AllocationAuditLog.builder()
                    .allocationId(id)
                    .action("DISPOSITION_CHANGED")
                    .performedBy(userId)
                    .previousValue(previousDisp == null ? null : previousDisp.name())
                    .newValue(request.getDisposition() == null ? null : request.getDisposition().name())
                    .reason(StringUtils.hasText(request.getReason()) ? request.getReason() : "Manually changed")
                    .build());
        }

        return allocationMapper.toResponse(allocationRepository.save(allocation));
    }

    @Override
    @Transactional
    public void softDeleteAllocation(UUID id, UUID userId) {
        allocationRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found: " + id));
        allocationRepository.softDelete(id, userId);
    }

    @Override
    @Transactional
    public void softDeleteAllocationsByFileUpload(UUID fileUploadId, UUID userId) {
        allocationRepository.softDeleteAllByFileUploadId(fileUploadId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllocationResponse> getAllocationsForBorrower(UUID borrowerId) {
        return allocationRepository.findByBorrowerIdAndIsDeletedFalse(borrowerId)
                .stream().map(allocationMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public List<AllocationResponse> bulkAssignToFo(UUID callerOrgId, BulkAssignToFoRequest request, UUID actorId) {
        if (callerOrgId == null) throw new BusinessException("Caller has no organization context");

        User fo = userRepository.findById(request.getAssignedToUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getAssignedToUserId()));
        if (!callerOrgId.equals(fo.getOrganizationId())) {
            throw new BusinessException("Field officer does not belong to this organization");
        }

        List<AllocationResponse> results = new ArrayList<>();
        for (UUID allocationId : request.getAllocationIds()) {
            Allocation a = allocationRepository.findByIdAndIsDeletedFalse(allocationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Allocation not found: " + allocationId));
            UUID caseOrgId = a.getOrganization() != null ? a.getOrganization().getId() : null;
            if (!callerOrgId.equals(caseOrgId)) {
                throw new ResourceNotFoundException("Allocation not found: " + allocationId);
            }
            a.setStatus(AllocationStatus.ASSIGNED);
            a.setAssignedToUserId(request.getAssignedToUserId());
            a.setAssignedAt(Instant.now());
            results.add(allocationMapper.toResponse(allocationRepository.save(a)));
            notificationService.create(fo.getId(), callerOrgId, NotificationType.FO_CASE_ASSIGNED,
                    "New case assigned: " + a.getLoanNumber(),
                    "You've been assigned loan " + a.getLoanNumber() + ".");
        }
        return results;
    }

    @Override
    @Transactional
    public AllocationResponse linkBorrower(UUID allocationId, UUID borrowerId, UUID actingUserId) {
        Allocation a = allocationRepository.findByIdAndIsDeletedFalse(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found: " + allocationId));
        if (!orgIsolationGuard.belongsToOrg(a.getOrganization().getId())) {
            throw new ResourceNotFoundException("Allocation not found: " + allocationId);
        }
        // Neither RLS nor a plain FK constraint catches this: RLS scopes by the CALLER's own org
        // context regardless of which resource is being touched, and FK checks in Postgres verify
        // referential integrity table-wide, not per-tenant -- so without this explicit check, a
        // lead could link this org's allocation to another organization's borrower record.
        var borrowerOrgId = borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Borrower not found: " + borrowerId))
                .getOrganizationId();
        if (!borrowerOrgId.equals(a.getOrganization().getId())) {
            throw new ResourceNotFoundException("Borrower not found: " + borrowerId);
        }
        a.setBorrowerId(borrowerId);
        return allocationMapper.toResponse(allocationRepository.save(a));
    }

    private void enrichListView(List<AllocationResponse> rows) {
        if (rows == null || rows.isEmpty()) return;

        Set<UUID> agentIds = new HashSet<>();
        for (AllocationResponse r : rows) {
            if (r.getAssignedToUserId() != null) agentIds.add(r.getAssignedToUserId());
            r.setEmi(extractEmi(r.getDynamicData()));
        }
        if (agentIds.isEmpty()) return;

        Map<UUID, String> names = userRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(User::getId, u -> {
                    String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
                    String last  = u.getLastName()  == null ? "" : u.getLastName().trim();
                    String full  = (first + " " + last).trim();
                    return full.isEmpty() ? null : full;
                }, (a, b) -> a));

        for (AllocationResponse r : rows) {
            if (r.getAssignedToUserId() != null) {
                r.setAgentName(names.get(r.getAssignedToUserId()));
            }
        }
    }

    private BigDecimal extractEmi(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return null;
        for (String key : EMI_KEYS) {
            Object v = data.get(key);
            BigDecimal parsed = coerceMoney(v);
            if (parsed != null) return parsed;
        }
        return null;
    }

    private BigDecimal coerceMoney(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "—".equals(s) || "-".equals(s)) return null;
        s = s.replace("₹", "").replace("Rs.", "").replace("Rs", "")
             .replace(",", "").replace("INR", "").trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Sort buildSort(String sortBy, String sortDirection) {
        return SafeSort.from(sortBy, sortDirection, SORTABLE_FIELDS, "createdAt");
    }
}
