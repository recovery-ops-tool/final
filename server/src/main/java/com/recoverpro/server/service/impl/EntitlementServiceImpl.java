package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Usage is checked live (COUNT query vs. the configured limit), not tracked in a maintained
 * counter column. A separately-maintained counter drifts from reality the moment any write path
 * forgets to increment/decrement it (soft-deletes, bulk imports, admin edits) -- a live count is
 * correct by construction, at the cost of a count query per check. At RecoverPro's actual traffic
 * this is the right trade, not premature optimization territory (Billing Ledger design doc §6).
 */
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;
    private final AllocationRepository allocationRepository;
    private final FileUploadRepository fileUploadRepository;
    private final ReportJobRepository reportJobRepository;

    /** TASK 20.4: matches the IST "billing month" convention already used by
     *  {@code ReportingServiceImpl} and {@code LucienTokenBudgetServiceImpl} -- a monthly cap that
     *  reset at UTC midnight would flip ~5.5 hours before the org's own business day does. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static Instant startOfThisMonth() {
        return YearMonth.now(IST).atDay(1).atStartOfDay(IST).toInstant();
    }

    @Override
    public boolean hasFeature(UUID organizationId, String flagKey) {
        return featureFlagService.isEnabled(organizationId, flagKey, false);
    }

    @Override
    public Optional<Long> getLimit(UUID organizationId, String limitKey) {
        return featureFlagService.getLimit(organizationId, limitKey);
    }

    @Override
    public boolean canCreateUser(UUID organizationId) {
        Optional<Long> limit = getLimit(organizationId, PlanFeatureMatrix.MAX_USERS);
        if (limit.isEmpty()) return true; // unlimited
        return userRepository.countByOrganizationId(organizationId) < limit.get();
    }

    @Override
    public boolean canCreateAllocations(UUID organizationId, long additionalCount) {
        Optional<Long> limit = getLimit(organizationId, PlanFeatureMatrix.MAX_ACTIVE_LOANS);
        if (limit.isEmpty()) return true; // unlimited
        return allocationRepository.countByOrgId(organizationId) + additionalCount <= limit.get();
    }

    @Override
    public boolean canUploadFile(UUID organizationId) {
        Optional<Long> limit = getLimit(organizationId, PlanFeatureMatrix.FILE_UPLOADS_PER_MONTH);
        if (limit.isEmpty()) return true; // unlimited
        return fileUploadRepository.countByOrganizationIdAndCreatedAtAfterAndIsDeletedFalse(
                organizationId, startOfThisMonth()) < limit.get();
    }

    @Override
    public boolean canUseStorage(UUID organizationId, long additionalBytes) {
        Optional<Long> limit = getLimit(organizationId, PlanFeatureMatrix.STORAGE_BYTES);
        if (limit.isEmpty()) return true; // unlimited
        long used = fileUploadRepository.sumFileSizeBytesByOrganizationIdAndIsDeletedFalse(organizationId);
        return used + additionalBytes <= limit.get();
    }

    @Override
    public boolean canGenerateReport(UUID organizationId) {
        Optional<Long> limit = getLimit(organizationId, PlanFeatureMatrix.REPORT_GENERATIONS_PER_MONTH);
        if (limit.isEmpty()) return true; // unlimited
        return reportJobRepository.countByOrganizationIdAndCreatedAtAfter(
                organizationId, startOfThisMonth()) < limit.get();
    }
}
