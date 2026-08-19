package com.recoverpro.server.service.impl;

import com.recoverpro.server.entity.FileUpload;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.enums.FileUploadStatus;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.ColumnSchemaRepository;
import com.recoverpro.server.repository.FileProcessingErrorRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.FileParsingService;
import com.recoverpro.server.service.FileStorageService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.importer.EntityImportProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN SP32: processFileAsync used to call autoAssignFromFile/cancelDroppedLoanAssignments
 * via plain self-invocation (this.method()), which bypasses the Spring proxy so their
 * @Transactional(REQUIRES_NEW) was silently ignored -- they ran in processFileAsync's own
 * transaction instead of a fresh one. Both were moved to FileUploadPostProcessingService, a
 * separate bean, so the call goes through the proxy and REQUIRES_NEW actually applies.
 */
@ExtendWith(MockitoExtension.class)
class FileProcessingServiceImplTest {

    @Mock private FileUploadRepository fileUploadRepository;
    @Mock private AllocationRepository allocationRepository;
    @Mock private ColumnSchemaRepository columnSchemaRepository;
    @Mock private FileProcessingErrorRepository fileProcessingErrorRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileParsingService fileParsingService;
    @Mock private FileStorageService fileStorageService;
    @Mock private FileUploadPostProcessingService fileUploadPostProcessingService;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;
    @Mock private EntitlementService entitlementService;
    @Mock private EntityImportProcessor<Object> allocationProcessor;

    private FileProcessingServiceImpl service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new FileProcessingServiceImpl(fileUploadRepository, allocationRepository,
                columnSchemaRepository, fileProcessingErrorRepository, organizationRepository,
                userRepository, fileParsingService, fileStorageService,
                fileUploadPostProcessingService, notificationService, auditService, entitlementService,
                List.of(allocationProcessor), meterRegistry);
        lenient().when(entitlementService.canCreateAllocations(any(), anyLong())).thenReturn(true);
    }

    @Test
    void processFileAsync_delegatesAutoAssignAndDroppedLoanCleanup_toSeparateBean() throws Exception {
        UUID fileUploadId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID uploadedBy = UUID.randomUUID();

        Organization org = new Organization();
        org.setId(orgId);
        org.setActive(true);

        FileUpload fileUpload = FileUpload.builder()
                .id(fileUploadId).organization(org).originalFilename("book.csv")
                .contentType("text/csv").uploadedByUserId(uploadedBy)
                .status(FileUploadStatus.PENDING)
                .build();

        when(allocationProcessor.supportedType()).thenReturn(UploadType.ALLOCATION);
        service.indexProcessors();

        when(fileUploadRepository.findByIdAndIsDeletedFalse(fileUploadId)).thenReturn(Optional.of(fileUpload));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(columnSchemaRepository.findAllActiveByOrganizationIdAndEntityType(orgId, UploadType.ALLOCATION))
                .thenReturn(List.of());
        when(fileStorageService.retrieve(fileUploadId)).thenReturn("data".getBytes());
        // fileParsingService.streamFile() is intentionally left unstubbed: Mockito's default
        // no-op for a void method means the scan/processing RowHandlers are never invoked,
        // which is exactly an empty-file parse (zero headers, zero rows).

        service.processFileAsync(fileUploadId, orgId);

        verify(fileUploadPostProcessingService).autoAssignFromFile(fileUploadId, orgId, uploadedBy);
        verify(fileUploadPostProcessingService).cancelDroppedLoanAssignments(fileUploadId, orgId);

        // SYSTEM 12 TASK 12.2: proves file_import_events_total actually appears in the registry
        // (the same registry /actuator/prometheus reads from) after exercising the code path --
        // the task's own literal acceptance criterion, not just that the code compiles.
        assertThat(meterRegistry.get("file_import_events_total")
                .tag("upload_type", "ALLOCATION").tag("outcome", "started")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("file_import_events_total")
                .tag("upload_type", "ALLOCATION").tag("outcome", "completed")
                .counter().count()).isEqualTo(1.0);
    }
}
