package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.response.FileProcessingErrorResponse;
import com.recoverpro.server.dto.response.FileUploadResponse;
import com.recoverpro.server.entity.FileUpload;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.FileUploadStatus;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.mapper.FileProcessingErrorMapper;
import com.recoverpro.server.mapper.FileUploadMapper;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.FileProcessingErrorRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    private final FileUploadRepository fileUploadRepository;
    private final OrganizationRepository organizationRepository;
    private final FileProcessingErrorRepository fileProcessingErrorRepository;
    private final AllocationRepository allocationRepository;
    private final FileValidationService fileValidationService;
    private final FileProcessingService fileProcessingService;
    private final FileUploadMapper fileUploadMapper;
    private final FileProcessingErrorMapper fileProcessingErrorMapper;
    private final FileStorageService fileStorageService;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final EntitlementService entitlementService;

    @Override
    @Transactional
    public FileUploadResponse initiateUpload(MultipartFile file, UUID organizationId, UUID userId,
                                             UploadType uploadType, boolean historicalImport) {
        log.info("Initiating {} upload for organization: {}, file: {}",
                uploadType, organizationId, file.getOriginalFilename());

        // TASK 20.4: checked before validateFile()'s own work (and well before the expensive
        // hash/store/process steps below) -- a plan-limit rejection should be the cheapest possible
        // failure, not the last one.
        if (!entitlementService.canUploadFile(organizationId)) {
            throw new BusinessException(
                    "Monthly file upload limit reached for this organization's plan. Upgrade your plan or contact support.");
        }
        if (!entitlementService.canUseStorage(organizationId, file.getSize())) {
            throw new BusinessException(
                    "Storage limit reached for this organization's plan. Delete old files, upgrade your plan, or contact support.");
        }

        fileValidationService.validateFile(file);
        String sha256Hash = fileValidationService.computeSha256Hash(file);

        // Scoped by type as well as hash: the same export can legitimately be imported once
        // per entity, and returning an earlier upload of a different type would silently
        // hand the caller the wrong record.
        if (fileValidationService.isDuplicateFile(sha256Hash, organizationId, uploadType)) {
            return fileUploadRepository
                    .findFirstBySha256HashAndOrganizationIdAndUploadTypeAndIsDeletedFalse(
                            sha256Hash, organizationId, uploadType)
                    .map(fileUploadMapper::toResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("Duplicate file record not found unexpectedly"));
        }

        Organization organization = organizationRepository.findById(organizationId)
                .filter(o -> o.isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));

        FileUpload fileUpload = FileUpload.builder()
                .organization(organization)
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .sha256Hash(sha256Hash)
                .status(FileUploadStatus.PENDING)
                .uploadType(uploadType)
                .isHistoricalImport(historicalImport)
                .uploadedByUserId(userId)
                .build();

        FileUpload saved = fileUploadRepository.save(fileUpload);
        log.info("Created FileUpload record with id: {}", saved.getId());

        auditLogService.logUserAction(userId, "FILE_UPLOAD_INITIATED",
                String.format("File: %s, Size: %d bytes, Org: %s",
                        file.getOriginalFilename(), file.getSize(), organizationId));
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.FILE_UPLOAD_INITIATED)
                .resourceType(AuditResourceType.FILE_UPLOAD)
                .resourceId(String.valueOf(saved.getId()))
                .organizationIdOverride(organizationId)
                .metadata(java.util.Map.of(
                        "filename", String.valueOf(file.getOriginalFilename()),
                        "sizeBytes", String.valueOf(file.getSize())))
                .build());

        fileStorageService.store(saved.getId(), file);
        fileUploadRepository.flush();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Transaction committed. Triggering async processing for fileUploadId: {}", saved.getId());
                fileProcessingService.processFileAsync(saved.getId(), organizationId);
            }
        });

        return fileUploadMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public FileUploadResponse getUploadStatus(UUID fileUploadId) {
        FileUpload fileUpload = fileUploadRepository.findByIdAndIsDeletedFalse(fileUploadId)
                .orElseThrow(() -> new ResourceNotFoundException("FileUpload not found: " + fileUploadId));
        return fileUploadMapper.toResponse(fileUpload);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<FileUploadResponse> getUploadsByOrganization(UUID organizationId, Pageable pageable) {
        Page<FileUpload> page = fileUploadRepository.findAllByOrganizationIdAndNotDeleted(organizationId, pageable);
        return PagedResponse.from(page.map(fileUploadMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<FileProcessingErrorResponse> getProcessingErrors(UUID fileUploadId, Pageable pageable) {
        fileUploadRepository.findByIdAndIsDeletedFalse(fileUploadId)
                .orElseThrow(() -> new ResourceNotFoundException("FileUpload not found: " + fileUploadId));
        var page = fileProcessingErrorRepository.findAllByFileUploadId(fileUploadId, pageable);
        return PagedResponse.from(page.map(fileProcessingErrorMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public String buildProcessingErrorsCsv(UUID fileUploadId) {
        fileUploadRepository.findByIdAndIsDeletedFalse(fileUploadId)
                .orElseThrow(() -> new ResourceNotFoundException("FileUpload not found: " + fileUploadId));
        List<com.recoverpro.server.entity.FileProcessingError> errors =
                fileProcessingErrorRepository.findAllByFileUploadIdOrderByRowNumberAsc(fileUploadId);

        StringBuilder csv = new StringBuilder("Row,Column,Error,Raw Value\n");
        for (com.recoverpro.server.entity.FileProcessingError error : errors) {
            csv.append(error.getRowNumber() != null ? error.getRowNumber() : "").append(',')
                    .append(csvField(error.getColumnName())).append(',')
                    .append(csvField(error.getErrorMessage())).append(',')
                    .append(csvField(error.getRawValue())).append('\n');
        }
        return csv.toString();
    }

    /**
     * columnName/errorMessage/rawValue can all echo attacker-controlled content straight from an
     * uploaded file's cells (rawValue especially -- it IS the raw cell). A value starting with
     * =, +, -, @, tab or CR is a formula-injection payload in Excel/Sheets once this CSV is
     * opened there, so it's neutralised with a leading apostrophe before the normal CSV quoting.
     */
    private static String csvField(String value) {
        if (value == null || value.isEmpty()) return "";
        if ("=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n");
        return needsQuoting ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    @Override
    @Transactional
    public void softDeleteFileUpload(UUID fileUploadId, UUID userId) {
        log.info("Soft deleting fileUpload: {} by user: {}", fileUploadId, userId);
        FileUpload existing = fileUploadRepository.findByIdAndIsDeletedFalse(fileUploadId)
                .orElseThrow(() -> new ResourceNotFoundException("FileUpload not found: " + fileUploadId));
        allocationRepository.softDeleteAllByFileUploadId(fileUploadId, userId);
        fileUploadRepository.softDelete(fileUploadId, userId);
        fileStorageService.delete(fileUploadId);
        auditLogService.logUserAction(userId, "FILE_UPLOAD_DELETED",
                String.format("FileUpload ID: %s", fileUploadId));
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.FILE_UPLOAD_DELETED)
                .resourceType(AuditResourceType.FILE_UPLOAD)
                .resourceId(fileUploadId.toString())
                .organizationIdOverride(existing.getOrganization() != null
                        ? existing.getOrganization().getId() : null)
                .build());
        log.info("Successfully soft deleted fileUpload: {} and all associated allocations", fileUploadId);
    }
}
