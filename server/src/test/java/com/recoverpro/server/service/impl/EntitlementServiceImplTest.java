package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.FeatureFlagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 20.1: {@link EntitlementServiceImpl} is the designated single entitlement
 * authority -- {@link com.recoverpro.server.aspect.RequiresFeatureAspect} now calls only this,
 * never {@link FeatureFlagService} directly. The one behavior that actually matters here is the
 * {@code defaultIfMissing} literal passed to {@code isEnabled}: it must stay {@code false} (fail
 * closed on an unresolvable/missing flag row), per the production standard ("fail CLOSED --
 * never default to allow").
 */
@ExtendWith(MockitoExtension.class)
class EntitlementServiceImplTest {

    @Mock private FeatureFlagService featureFlagService;
    @Mock private UserRepository userRepository;
    @Mock private AllocationRepository allocationRepository;
    @Mock private FileUploadRepository fileUploadRepository;
    @Mock private ReportJobRepository reportJobRepository;

    private EntitlementServiceImpl service;

    private void setUp() {
        service = new EntitlementServiceImpl(featureFlagService, userRepository, allocationRepository,
                fileUploadRepository, reportJobRepository);
    }

    @Test
    void hasFeature_missingFlagRow_failsClosed() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.isEnabled(orgId, "LUCIEN_AI", false)).thenReturn(false);

        boolean result = service.hasFeature(orgId, "LUCIEN_AI");

        assertThat(result).isFalse();
        verify(featureFlagService).isEnabled(orgId, "LUCIEN_AI", false);
    }

    @Test
    void hasFeature_enabledRow_returnsTrue() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.isEnabled(orgId, "LUCIEN_AI", false)).thenReturn(true);

        assertThat(service.hasFeature(orgId, "LUCIEN_AI")).isTrue();
    }

    @Test
    void canCreateUser_noLimitRow_meansUnlimited() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.MAX_USERS)).thenReturn(Optional.empty());

        assertThat(service.canCreateUser(orgId)).isTrue();
    }

    @Test
    void canCreateUser_atLimit_denies() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.MAX_USERS)).thenReturn(Optional.of(5L));
        when(userRepository.countByOrganizationId(orgId)).thenReturn(5L);

        assertThat(service.canCreateUser(orgId)).isFalse();
    }

    @Test
    void canCreateUser_belowLimit_allows() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.MAX_USERS)).thenReturn(Optional.of(5L));
        when(userRepository.countByOrganizationId(orgId)).thenReturn(4L);

        assertThat(service.canCreateUser(orgId)).isTrue();
    }

    @Test
    void canCreateAllocations_wouldExceedLimit_denies() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.MAX_ACTIVE_LOANS)).thenReturn(Optional.of(100L));
        when(allocationRepository.countByOrgId(orgId)).thenReturn(95L);

        assertThat(service.canCreateAllocations(orgId, 10)).isFalse();
    }

    @Test
    void canCreateAllocations_withinLimit_allows() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.MAX_ACTIVE_LOANS)).thenReturn(Optional.of(100L));
        when(allocationRepository.countByOrgId(orgId)).thenReturn(95L);

        assertThat(service.canCreateAllocations(orgId, 5)).isTrue();
    }

    // ── TASK 20.4: usage-cost caps ────────────────────────────────────────────

    @Test
    void canUploadFile_noLimitRow_meansUnlimited() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.FILE_UPLOADS_PER_MONTH)).thenReturn(Optional.empty());

        assertThat(service.canUploadFile(orgId)).isTrue();
    }

    @Test
    void canUploadFile_atMonthlyLimit_denies() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.FILE_UPLOADS_PER_MONTH)).thenReturn(Optional.of(50L));
        when(fileUploadRepository.countByOrganizationIdAndCreatedAtAfterAndIsDeletedFalse(eq(orgId), any()))
                .thenReturn(50L);

        assertThat(service.canUploadFile(orgId)).isFalse();
    }

    @Test
    void canUploadFile_belowMonthlyLimit_allows() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.FILE_UPLOADS_PER_MONTH)).thenReturn(Optional.of(50L));
        when(fileUploadRepository.countByOrganizationIdAndCreatedAtAfterAndIsDeletedFalse(eq(orgId), any()))
                .thenReturn(49L);

        assertThat(service.canUploadFile(orgId)).isTrue();
    }

    @Test
    void canUseStorage_noLimitRow_meansUnlimited() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.STORAGE_BYTES)).thenReturn(Optional.empty());

        assertThat(service.canUseStorage(orgId, 999_999_999_999L)).isTrue();
    }

    @Test
    void canUseStorage_wouldExceedCap_denies() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.STORAGE_BYTES)).thenReturn(Optional.of(1_000L));
        when(fileUploadRepository.sumFileSizeBytesByOrganizationIdAndIsDeletedFalse(orgId)).thenReturn(950L);

        assertThat(service.canUseStorage(orgId, 51L)).isFalse();
    }

    @Test
    void canUseStorage_withinCap_allows() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.STORAGE_BYTES)).thenReturn(Optional.of(1_000L));
        when(fileUploadRepository.sumFileSizeBytesByOrganizationIdAndIsDeletedFalse(orgId)).thenReturn(950L);

        assertThat(service.canUseStorage(orgId, 50L)).isTrue();
    }

    @Test
    void canGenerateReport_atMonthlyLimit_denies() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.REPORT_GENERATIONS_PER_MONTH)).thenReturn(Optional.of(20L));
        when(reportJobRepository.countByOrganizationIdAndCreatedAtAfter(eq(orgId), any())).thenReturn(20L);

        assertThat(service.canGenerateReport(orgId)).isFalse();
    }

    @Test
    void canGenerateReport_belowMonthlyLimit_allows() {
        setUp();
        UUID orgId = UUID.randomUUID();
        when(featureFlagService.getLimit(orgId, PlanFeatureMatrix.REPORT_GENERATIONS_PER_MONTH)).thenReturn(Optional.of(20L));
        when(reportJobRepository.countByOrganizationIdAndCreatedAtAfter(eq(orgId), any())).thenReturn(19L);

        assertThat(service.canGenerateReport(orgId)).isTrue();
    }
}
