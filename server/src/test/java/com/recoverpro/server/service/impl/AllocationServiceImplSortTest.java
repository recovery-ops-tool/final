package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.AllocationFilterRequest;
import com.recoverpro.server.mapper.AllocationMapper;
import com.recoverpro.server.repository.AllocationAuditLogRepository;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.BorrowerRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.encryption.LookupHashService;
import com.recoverpro.server.service.ActiveDatasetResolver;
import com.recoverpro.server.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 26.2: {@code buildSort()} used to pass {@code sortBy} straight from the request into
 * {@code Sort.by()} with zero validation -- a caller could request {@code sortBy=borrowerName} (an
 * {@code EncryptedStringConverter} field) or any nonexistent property, which
 * {@code findAllWithFilters}'s JPQL {@code ORDER BY} would fail to resolve at runtime (an
 * uncaught 500, not the 400 TASK 26.2's ACCEPTANCE requires). Targets only this new validation --
 * not a full retrofit of {@code AllocationServiceImplTest} coverage, which doesn't exist yet.
 */
@ExtendWith(MockitoExtension.class)
class AllocationServiceImplSortTest {

    @Mock private AllocationRepository allocationRepository;
    @Mock private AllocationAuditLogRepository allocationAuditLogRepository;
    @Mock private AllocationMapper allocationMapper;
    @Mock private UserRepository userRepository;
    @Mock private BorrowerRepository borrowerRepository;
    @Mock private OrgIsolationGuard orgIsolationGuard;
    @Mock private ActiveDatasetResolver activeDatasetResolver;
    @Mock private NotificationService notificationService;
    @Mock private LookupHashService lookupHashService;

    private AllocationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AllocationServiceImpl(allocationRepository, allocationAuditLogRepository,
                allocationMapper, userRepository, borrowerRepository, orgIsolationGuard,
                activeDatasetResolver, notificationService, lookupHashService);
    }

    @Test
    void getAllocations_disallowedSortField_rejectsBeforeAnyQuery() {
        AllocationFilterRequest request = AllocationFilterRequest.builder()
                .organizationId(UUID.randomUUID())
                .fileUploadId(UUID.randomUUID())
                .page(0).size(20)
                .sortBy("borrowerName")
                .sortDirection("desc")
                .build();

        assertThatThrownBy(() -> service.getAllocations(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("borrowerName");
    }

    @Test
    void getAllocations_unknownSortField_rejectsRatherThanRuntimeFailure() {
        AllocationFilterRequest request = AllocationFilterRequest.builder()
                .organizationId(UUID.randomUUID())
                .fileUploadId(UUID.randomUUID())
                .page(0).size(20)
                .sortBy("someTypoedColumn")
                .build();

        assertThatThrownBy(() -> service.getAllocations(request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getAllocations_allowedSortField_proceedsToQuery() {
        when(allocationRepository.findAllWithFilters(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        AllocationFilterRequest request = AllocationFilterRequest.builder()
                .organizationId(UUID.randomUUID())
                .fileUploadId(UUID.randomUUID())
                .page(0).size(20)
                .sortBy("outstandingAmount")
                .sortDirection("asc")
                .build();

        assertThatCode(() -> service.getAllocations(request)).doesNotThrowAnyException();
    }
}
