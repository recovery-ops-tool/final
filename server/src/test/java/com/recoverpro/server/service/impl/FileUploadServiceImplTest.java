package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.mapper.FileProcessingErrorMapper;
import com.recoverpro.server.mapper.FileUploadMapper;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.FileProcessingErrorRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.FileProcessingService;
import com.recoverpro.server.service.FileStorageService;
import com.recoverpro.server.service.FileValidationService;
import com.recoverpro.server.service.UserActionAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 20.4: targets only the new entitlement checks added to {@code initiateUpload} --
 * monthly upload-count cap and total-storage cap, both checked before any of the existing
 * (validation, hashing, storage, async processing) work runs. Not a full retrofit of this class's
 * pre-existing behavior, which has no prior test file.
 */
@ExtendWith(MockitoExtension.class)
class FileUploadServiceImplTest {

    @Mock private FileUploadRepository fileUploadRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private FileProcessingErrorRepository fileProcessingErrorRepository;
    @Mock private AllocationRepository allocationRepository;
    @Mock private FileValidationService fileValidationService;
    @Mock private FileProcessingService fileProcessingService;
    @Mock private FileUploadMapper fileUploadMapper;
    @Mock private FileProcessingErrorMapper fileProcessingErrorMapper;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private EntitlementService entitlementService;

    private FileUploadServiceImpl service;
    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new FileUploadServiceImpl(fileUploadRepository, organizationRepository,
                fileProcessingErrorRepository, allocationRepository, fileValidationService,
                fileProcessingService, fileUploadMapper, fileProcessingErrorMapper, fileStorageService,
                auditLogService, auditService, entitlementService);
        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    void initiateUpload_atMonthlyUploadLimit_rejectsBeforeValidation() {
        when(entitlementService.canUploadFile(orgId)).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2".getBytes());

        assertThatThrownBy(() -> service.initiateUpload(file, orgId, userId, UploadType.ALLOCATION, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("upload limit");

        verifyNoInteractions(fileValidationService, fileStorageService, organizationRepository);
        verify(entitlementService, never()).canUseStorage(any(), anyLong());
    }

    @Test
    void initiateUpload_wouldExceedStorageCap_rejectsBeforeValidation() {
        when(entitlementService.canUploadFile(orgId)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2".getBytes());
        when(entitlementService.canUseStorage(orgId, file.getSize())).thenReturn(false);

        assertThatThrownBy(() -> service.initiateUpload(file, orgId, userId, UploadType.ALLOCATION, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Storage limit");

        verifyNoInteractions(fileValidationService, fileStorageService, organizationRepository);
    }
}
