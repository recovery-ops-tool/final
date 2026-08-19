package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.entity.*;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.FileUploadStatus;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.repository.*;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.FileParsingService;
import com.recoverpro.server.service.FileProcessingService;
import com.recoverpro.server.service.FileStorageService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.RowHandler;
import com.recoverpro.server.service.importer.EntityImportProcessor;
import com.recoverpro.server.service.importer.ImportContext;
import com.recoverpro.server.service.importer.ImportFieldSpec;
import com.recoverpro.server.service.importer.ImportValues;
import com.recoverpro.server.service.importer.RowValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessingServiceImpl implements FileProcessingService {

    private static final String[] LOAN_NUMBER_ALIASES = {"loan_number", "loan number", "loannumber", "loan_no"};
    private static final String[] AGENT_EMAIL_ALIASES = {"agent_email", "agent email", "agentemail", "email"};

    private final FileUploadRepository fileUploadRepository;
    private final AllocationRepository allocationRepository;
    private final ColumnSchemaRepository columnSchemaRepository;
    private final FileProcessingErrorRepository fileProcessingErrorRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final FileParsingService fileParsingService;
    private final FileStorageService fileStorageService;
    private final FileUploadPostProcessingService fileUploadPostProcessingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final EntitlementService entitlementService;
    private final List<EntityImportProcessor<?>> importProcessors;
    private final MeterRegistry meterRegistry;

    private Map<UploadType, EntityImportProcessor<?>> processorsByType;

    @Value("${application.file.batch-size:500}")
    private int batchSize;

    @Value("${application.file.max-rows:50000}")
    private int maxRows;

    @PostConstruct
    void indexProcessors() {
        processorsByType = importProcessors.stream()
                .collect(Collectors.toMap(EntityImportProcessor::supportedType, Function.identity()));
        log.info("Registered {} import processors: {}", processorsByType.size(), processorsByType.keySet());
    }

    @Override
    @Async("fileProcessingExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processFileAsync(UUID fileUploadId, UUID organizationId) {
        log.info("Starting async processing for fileUploadId: {}", fileUploadId);

        FileUpload fileUpload = fileUploadRepository.findByIdAndIsDeletedFalse(fileUploadId)
                .orElseThrow(() -> new ResourceNotFoundException("FileUpload not found: " + fileUploadId));

        Organization organization = organizationRepository.findById(organizationId)
                .filter(o -> o.isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));

        UploadType uploadType = fileUpload.getUploadType();
        EntityImportProcessor<?> processor = processorsByType.get(uploadType);
        if (processor == null) {
            throw new BusinessException("No import processor registered for upload type: " + uploadType);
        }

        try {
            fileUpload.setStatus(FileUploadStatus.PROCESSING);
            fileUploadRepository.save(fileUpload);
            auditFileProcessing(fileUpload, AuditAction.FILE_PROCESSING_STARTED, AuditResult.SUCCESS, null);
            fileImportCounter(uploadType, "started").increment();

            List<ColumnSchema> columnSchemas = columnSchemaRepository
                    .findAllActiveByOrganizationIdAndEntityType(organizationId, uploadType);

            log.info("Found {} column schemas for organization {} and type {}",
                    columnSchemas.size(), organizationId, uploadType);

            byte[] fileBytes = fileStorageService.retrieve(fileUploadId);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException("File bytes not found in storage for fileUploadId: " + fileUploadId);
            }

            MultipartFile multipartFile = new ByteArrayMultipartFile(
                    fileBytes, fileUpload.getOriginalFilename(), fileUpload.getContentType());

            // Pass 1: a lightweight scan for the row count and the loan-number/agent-email
            // values needed for bulk prefetch. Each row is handed to the scan callback and
            // discarded immediately -- unlike the old parseFile(), nothing here accumulates a
            // full List<Map<String,String>> for the file's duration (see FileProcessingServiceImplTest
            // and SYSTEM-PLAN 35.3 for why: that list, not the parse step itself, was what grew
            // proportional to file size and stayed live for the whole processing run).
            ScanResult scan = scanFile(multipartFile, processor);
            log.info("Scanned {} rows from file: {}. Headers: {}",
                    scan.totalRows, fileUpload.getOriginalFilename(), scan.headers);

            if (scan.totalRows > maxRows) {
                throw new BusinessException("File exceeds maximum allowed rows of " + maxRows);
            }

            // Checked upfront against the whole file, not per-row: rejecting the entire import
            // with a clear reason is better than silently truncating partway through a batch.
            if (uploadType == UploadType.ALLOCATION
                    && !entitlementService.canCreateAllocations(organizationId, scan.totalRows)) {
                throw new BusinessException(
                        "This import would exceed your plan's active-loan limit. Reduce the file "
                                + "size, close out existing cases, or upgrade your plan.");
            }

            if (scan.totalRows > 0) {
                assertRequiredHeadersPresent(processor, new LinkedHashSet<>(scan.headers));
            }

            fileUpload.setTotalRows(scan.totalRows);
            fileUploadRepository.save(fileUpload);

            if (scan.totalRows == 0) {
                finalizeEmptyFile(fileUpload);
            } else {
                Map<String, Allocation> allocationsByLoanNumber = processor.requiresAllocationLookup()
                        ? prefetchAllocationsByNumbers(organizationId, scan.loanNumbers) : Map.of();
                Map<String, User> usersByEmail = processor.requiresAgentLookup()
                        ? prefetchAgentsByEmails(scan.agentEmails) : Map.of();

                // Pass 2: the real processing pass, streaming the same in-memory bytes a second
                // time (ByteArrayMultipartFile.getInputStream() is cheaply re-readable).
                processRowsStreaming(processor, fileUpload, organization, columnSchemas, multipartFile,
                        scan.headers, scan.totalRows, allocationsByLoanNumber, usersByEmail);
            }

            // Auto-assignment and carry-over cleanup are allocation-book operations. Running
            // cancelDroppedLoanAssignments after, say, a collections import would cancel every
            // assignment whose loan simply isn't mentioned in that file.
            if (uploadType == UploadType.ALLOCATION) {
                fileUploadPostProcessingService.autoAssignFromFile(
                        fileUploadId, organizationId, fileUpload.getUploadedByUserId());
                fileUploadPostProcessingService.cancelDroppedLoanAssignments(fileUploadId, organizationId);
                notificationService.createForOrgRole(organizationId, PlatformConstants.ROLE_ORG_ADMIN,
                        NotificationType.ORG_ALLOCATION_UPLOAD_DONE,
                        "Allocation upload finished: " + fileUpload.getOriginalFilename(),
                        fileUpload.getSuccessfulRows() + " of " + fileUpload.getTotalRows()
                                + " rows imported successfully (" + fileUpload.getFailedRows() + " failed).");
            }

            fileStorageService.delete(fileUploadId);

        } catch (Exception e) {
            log.error("Failed to process file: {}", fileUploadId, e);
            fileUpload.setStatus(FileUploadStatus.FAILED);
            fileUpload.setErrorMessage(e.getMessage());
            fileUploadRepository.save(fileUpload);
            auditFileProcessing(fileUpload, AuditAction.FILE_PROCESSING_FAILED, AuditResult.FAILURE, e.getMessage());
            fileImportCounter(uploadType, "failed").increment();
        }
    }

    /**
     * SYSTEM 12 TASK 12.2: file_import_events_total{upload_type, outcome}. Tagged by upload_type
     * only (a small fixed enum, ALLOCATION/COLLECTION/VISIT/PTP) -- not by organization, which
     * would blow up cardinality with every new tenant (task's own guidance: prefer a bounded
     * dimension like plan tier over unbounded ones like org id).
     */
    private io.micrometer.core.instrument.Counter fileImportCounter(UploadType uploadType, String outcome) {
        return io.micrometer.core.instrument.Counter.builder("file_import_events_total")
                .tag("upload_type", uploadType.name())
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    /** file_import_rows_processed_total{upload_type, result}, result in {successful, failed}. */
    private void recordRowsProcessed(UploadType uploadType, int successfulRows, int failedRows) {
        io.micrometer.core.instrument.Counter.builder("file_import_rows_processed_total")
                .tag("upload_type", uploadType.name())
                .tag("result", "successful")
                .register(meterRegistry)
                .increment(successfulRows);
        io.micrometer.core.instrument.Counter.builder("file_import_rows_processed_total")
                .tag("upload_type", uploadType.name())
                .tag("result", "failed")
                .register(meterRegistry)
                .increment(failedRows);
    }

    /** Bounded to counts/status, never row content -- see FILE_PROCESSING_* actions in AuditAction. */
    private void auditFileProcessing(FileUpload fileUpload, AuditAction action, AuditResult result, String reason) {
        auditFileProcessing(fileUpload, action, result, reason, fileUpload.getTotalRows(),
                fileUpload.getSuccessfulRows(), fileUpload.getFailedRows());
    }

    /**
     * Explicit-counts overload: {@code updateProgress()} writes final row counts via a bulk
     * repository query that never touches the in-memory {@code fileUpload} entity, so the
     * zero-arg overload above would read stale (pre-processing) counts if used after that call.
     */
    private void auditFileProcessing(FileUpload fileUpload, AuditAction action, AuditResult result, String reason,
                                     Integer totalRows, Integer successfulRows, Integer failedRows) {
        auditService.record(AuditEventRequest.builder()
                .action(action)
                .resourceType(AuditResourceType.FILE_UPLOAD)
                .resourceId(fileUpload.getId().toString())
                .result(result)
                .reason(reason)
                .correlationId(fileUpload.getId().toString())
                .actorUserIdOverride(fileUpload.getUploadedByUserId())
                .actorTypeOverride(AuditActorType.BACKGROUND_JOB)
                .organizationIdOverride(fileUpload.getOrganization() != null
                        ? fileUpload.getOrganization().getId() : null)
                .metadata(Map.of(
                        "totalRows", String.valueOf(totalRows),
                        "successfulRows", String.valueOf(successfulRows),
                        "failedRows", String.valueOf(failedRows)))
                .build());
    }

    /** Mutable running counts shared between the List-based and streaming processing paths. */
    private static final class RowCounters {
        int processedRows;
        int successfulRows;
        int failedRows;
        int skippedRows;
    }

    /**
     * Validates and maps exactly one row, updating {@code counters} and appending to
     * {@code batch}/{@code batchErrors}. Extracted so the List-based path
     * ({@link #processRowsInBatches}, still called directly with a hand-built list by
     * FileProcessingBorrowerLinkTest) and the streaming path ({@link #processRowsStreaming}) run
     * the exact same per-row logic instead of two copies that could drift apart.
     */
    private <T> void handleRow(EntityImportProcessor<T> processor, ImportContext context,
                               List<ColumnSchema> columnSchemas, Map<String, String> headerMappings,
                               FileUpload fileUpload, Map<String, String> rowData, int displayRowNumber,
                               List<T> batch, List<FileProcessingError> batchErrors, RowCounters counters) {
        try {
            List<FileProcessingError> rowErrors = validateRowDynamic(
                    rowData, columnSchemas, headerMappings, fileUpload, displayRowNumber);

            if (!rowErrors.isEmpty()) {
                batchErrors.addAll(rowErrors);
                counters.failedRows++;
            } else {
                T entity = processor.mapRow(rowData, displayRowNumber, context);
                if (entity == null) {
                    // Already imported by an earlier upload - not an error, just nothing to do.
                    counters.skippedRows++;
                    counters.successfulRows++;
                } else {
                    batch.add(entity);
                    counters.successfulRows++;
                }
            }
        } catch (RowValidationException e) {
            for (RowValidationException.FieldError fe : e.getFieldErrors()) {
                batchErrors.add(buildError(fileUpload, displayRowNumber,
                        fe.column(), fe.message(), fe.rawValue()));
            }
            counters.failedRows++;
        } catch (Exception e) {
            log.warn("Error processing row {}: {}", displayRowNumber, e.getMessage());
            batchErrors.add(FileProcessingError.builder()
                    .fileUpload(fileUpload)
                    .rowNumber(displayRowNumber)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .build());
            counters.failedRows++;
        }
        counters.processedRows++;
    }

    /** Flushes a full batch/error-batch and reports progress, shared by both processing paths. */
    private <T> void maybeFlush(EntityImportProcessor<T> processor, ImportContext context,
                                List<T> batch, List<FileProcessingError> batchErrors,
                                RowCounters counters, FileUpload fileUpload) {
        if (batch.size() >= batchSize) {
            processor.persistBatch(batch, context);
            batch.clear();
        }
        if (batchErrors.size() >= batchSize) {
            fileProcessingErrorRepository.saveAll(batchErrors);
            batchErrors.clear();
        }
        if (counters.processedRows % batchSize == 0) {
            updateProgress(fileUpload.getId(), counters.processedRows, counters.successfulRows,
                    counters.failedRows, FileUploadStatus.PROCESSING);
            log.info("Progress: {} rows processed", counters.processedRows);
        }
    }

    private void finalizeEmptyFile(FileUpload fileUpload) {
        fileUpload.setStatus(FileUploadStatus.COMPLETED);
        fileUpload.setTotalRows(0);
        fileUpload.setSuccessfulRows(0);
        fileUpload.setFailedRows(0);
        fileUploadRepository.save(fileUpload);
        auditFileProcessing(fileUpload, AuditAction.FILE_PROCESSING_COMPLETED, AuditResult.SUCCESS, null);
        fileImportCounter(fileUpload.getUploadType(), "completed").increment();
    }

    /** Flushes any remainder, computes the final status, and audits -- shared tail of both paths. */
    private <T> void finalizeProcessing(EntityImportProcessor<T> processor, ImportContext context,
                                        FileUpload fileUpload, List<T> batch, List<FileProcessingError> batchErrors,
                                        RowCounters counters, int totalRows) {
        if (!batch.isEmpty()) processor.persistBatch(batch, context);
        if (!batchErrors.isEmpty()) fileProcessingErrorRepository.saveAll(batchErrors);

        FileUploadStatus finalStatus = counters.failedRows == 0
                ? FileUploadStatus.COMPLETED
                : (counters.successfulRows == 0 ? FileUploadStatus.FAILED : FileUploadStatus.PARTIALLY_COMPLETED);

        updateProgress(fileUpload.getId(), totalRows, counters.successfulRows, counters.failedRows, finalStatus);
        log.info("Processing complete for {}. Status: {}. Success: {}, Failed: {}, Skipped as duplicate: {}",
                fileUpload.getUploadType(), finalStatus, counters.successfulRows, counters.failedRows,
                counters.skippedRows);

        AuditAction finalAction = switch (finalStatus) {
            case COMPLETED -> AuditAction.FILE_PROCESSING_COMPLETED;
            case FAILED -> AuditAction.FILE_PROCESSING_FAILED;
            default -> AuditAction.FILE_PROCESSING_PARTIALLY_FAILED;
        };
        AuditResult finalResult = finalStatus == FileUploadStatus.COMPLETED ? AuditResult.SUCCESS
                : (finalStatus == FileUploadStatus.FAILED ? AuditResult.FAILURE : AuditResult.PARTIAL);
        auditFileProcessing(fileUpload, finalAction, finalResult,
                finalStatus == FileUploadStatus.COMPLETED ? null
                        : counters.failedRows + " of " + totalRows + " rows failed validation",
                totalRows, counters.successfulRows, counters.failedRows);

        String outcome = switch (finalStatus) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            default -> "partially_failed";
        };
        fileImportCounter(fileUpload.getUploadType(), outcome).increment();
        recordRowsProcessed(fileUpload.getUploadType(), counters.successfulRows, counters.failedRows);
    }

    /**
     * List-based processing entry point. Kept with this exact signature and behaviour --
     * FileProcessingBorrowerLinkTest calls it directly with a hand-built list -- but its body now
     * shares {@link #handleRow}/{@link #maybeFlush}/{@link #finalizeProcessing} with the streaming
     * path used by real uploads (see {@link #processRowsStreaming}) rather than duplicating them.
     */
    @Transactional
    public <T> void processRowsInBatches(EntityImportProcessor<T> processor,
                                         FileUpload fileUpload,
                                         Organization organization,
                                         List<ColumnSchema> columnSchemas,
                                         List<Map<String, String>> allRows) {
        int totalRows = allRows.size();
        if (totalRows == 0) {
            finalizeEmptyFile(fileUpload);
            return;
        }

        List<String> columnOrder = new ArrayList<>(allRows.get(0).keySet());
        fileUpload.setColumnOrder(columnOrder);
        fileUploadRepository.save(fileUpload);

        Map<String, String> headerMappings = buildHeaderMappings(allRows.get(0).keySet(), columnSchemas);
        log.info("Header mappings: {}", headerMappings);

        ImportContext context = ImportContext.builder()
                .organization(organization)
                .fileUpload(fileUpload)
                .importedByUserId(fileUpload.getUploadedByUserId())
                .historicalImport(Boolean.TRUE.equals(fileUpload.getIsHistoricalImport()))
                .columnSchemas(columnSchemas)
                .headerMappings(headerMappings)
                .allocationsByLoanNumber(processor.requiresAllocationLookup()
                        ? prefetchAllocations(organization.getId(), allRows) : Map.of())
                .usersByEmail(processor.requiresAgentLookup()
                        ? prefetchAgents(allRows) : Map.of())
                .build();

        List<T> batch = new ArrayList<>();
        List<FileProcessingError> batchErrors = new ArrayList<>();
        RowCounters counters = new RowCounters();

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            handleRow(processor, context, columnSchemas, headerMappings, fileUpload,
                    allRows.get(rowIndex), rowIndex + 2, batch, batchErrors, counters);
            maybeFlush(processor, context, batch, batchErrors, counters, fileUpload);
        }

        finalizeProcessing(processor, context, fileUpload, batch, batchErrors, counters, totalRows);
    }

    /** Wildcard entry point for the real upload path -- see {@link #streamProcessRows} for the typed body. */
    @SuppressWarnings("unchecked")
    private void processRowsStreaming(EntityImportProcessor<?> processor, FileUpload fileUpload,
                                      Organization organization, List<ColumnSchema> columnSchemas,
                                      MultipartFile multipartFile, List<String> headers, int totalRows,
                                      Map<String, Allocation> allocationsByLoanNumber, Map<String, User> usersByEmail) {
        streamProcessRows((EntityImportProcessor<Object>) processor, fileUpload, organization, columnSchemas,
                multipartFile, headers, totalRows, allocationsByLoanNumber, usersByEmail);
    }

    /**
     * The memory-safe path real uploads take: streams the file a second time (the scan pass
     * already happened in {@link #scanFile}) instead of iterating a pre-built List, so
     * batch/batchErrors -- capped at {@code batchSize} -- are the only state that doesn't scale
     * with file size, regardless of whether the file has 50 rows or the configured max.
     */
    @Transactional
    public <T> void streamProcessRows(EntityImportProcessor<T> processor, FileUpload fileUpload,
                                      Organization organization, List<ColumnSchema> columnSchemas,
                                      MultipartFile multipartFile, List<String> headers, int totalRows,
                                      Map<String, Allocation> allocationsByLoanNumber, Map<String, User> usersByEmail) {
        fileUpload.setColumnOrder(headers);
        fileUploadRepository.save(fileUpload);

        Map<String, String> headerMappings = buildHeaderMappings(new LinkedHashSet<>(headers), columnSchemas);
        log.info("Header mappings: {}", headerMappings);

        ImportContext context = ImportContext.builder()
                .organization(organization)
                .fileUpload(fileUpload)
                .importedByUserId(fileUpload.getUploadedByUserId())
                .historicalImport(Boolean.TRUE.equals(fileUpload.getIsHistoricalImport()))
                .columnSchemas(columnSchemas)
                .headerMappings(headerMappings)
                .allocationsByLoanNumber(allocationsByLoanNumber)
                .usersByEmail(usersByEmail)
                .build();

        List<T> batch = new ArrayList<>();
        List<FileProcessingError> batchErrors = new ArrayList<>();
        RowCounters counters = new RowCounters();

        fileParsingService.streamFile(multipartFile, new RowHandler() {
            @Override public void onHeaders(List<String> ignoredHeaders) {
                // Already captured by the scan pass; nothing to do here.
            }

            @Override public void onRow(Map<String, String> row, int dataRowIndex) {
                handleRow(processor, context, columnSchemas, headerMappings, fileUpload,
                        row, dataRowIndex + 2, batch, batchErrors, counters);
                maybeFlush(processor, context, batch, batchErrors, counters, fileUpload);
            }
        });

        finalizeProcessing(processor, context, fileUpload, batch, batchErrors, counters, totalRows);
    }

    /** Result of the lightweight pre-scan pass: {@link #scanFile}. */
    private static final class ScanResult {
        List<String> headers = List.of();
        int totalRows;
        final Set<String> loanNumbers = new LinkedHashSet<>();
        final Set<String> agentEmails = new LinkedHashSet<>();
    }

    /**
     * Streams the file once to learn the row count and the loan-number/agent-email values the
     * bulk prefetch needs, without holding any row beyond the callback that receives it -- this
     * is what lets {@link #processFileAsync} learn totalRows (for the maxRows and entitlement
     * checks) up front without materializing every row first.
     */
    private ScanResult scanFile(MultipartFile file, EntityImportProcessor<?> processor) {
        ScanResult result = new ScanResult();
        boolean wantLoanNumbers = processor.requiresAllocationLookup();
        boolean wantEmails = processor.requiresAgentLookup();
        fileParsingService.streamFile(file, new RowHandler() {
            @Override public void onHeaders(List<String> headers) {
                result.headers = headers;
            }

            @Override public void onRow(Map<String, String> row, int dataRowIndex) {
                result.totalRows++;
                if (wantLoanNumbers) {
                    String loanNumber = ImportValues.find(row, LOAN_NUMBER_ALIASES);
                    if (loanNumber != null && !loanNumber.isBlank()) {
                        result.loanNumbers.add(loanNumber.trim());
                    }
                }
                if (wantEmails) {
                    String email = ImportValues.find(row, AGENT_EMAIL_ALIASES);
                    if (email != null && !email.isBlank()) {
                        result.agentEmails.add(email.trim().toLowerCase());
                    }
                }
            }
        });
        return result;
    }

    /**
     * Rejects the file before any row is touched when a required column is missing entirely.
     * Per-row validation would otherwise emit the same "column not found" error thousands of
     * times over, burying the one fact the user needs: which header to add.
     */
    private void assertRequiredHeadersPresent(EntityImportProcessor<?> processor, Set<String> headers) {
        List<String> missing = processor.fieldSpecs().stream()
                .filter(ImportFieldSpec::required)
                .filter(spec -> !ImportValues.hasColumn(headers, spec))
                .map(spec -> spec.label() + " (" + spec.name() + ")")
                .collect(Collectors.toList());

        if (!missing.isEmpty()) {
            throw new BusinessException("This file is missing required column(s): "
                    + String.join(", ", missing)
                    + ". Download the " + processor.supportedType() + " template for the expected format.");
        }
    }

    private Map<String, Allocation> prefetchAllocations(UUID organizationId, List<Map<String, String>> allRows) {
        Set<String> loanNumbers = allRows.stream()
                .map(row -> ImportValues.find(row, LOAN_NUMBER_ALIASES))
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return prefetchAllocationsByNumbers(organizationId, loanNumbers);
    }

    private Map<String, User> prefetchAgents(List<Map<String, String>> allRows) {
        Set<String> emails = allRows.stream()
                .map(row -> ImportValues.find(row, AGENT_EMAIL_ALIASES))
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return prefetchAgentsByEmails(emails);
    }

    private Map<String, Allocation> prefetchAllocationsByNumbers(UUID organizationId, Set<String> loanNumbers) {
        if (loanNumbers.isEmpty()) return Map.of();

        Map<String, Allocation> byLoanNumber = allocationRepository
                .findByOrganizationIdAndLoanNumberIn(organizationId, new ArrayList<>(loanNumbers))
                .stream()
                .collect(Collectors.toMap(Allocation::getLoanNumber, a -> a, (a, b) -> a));
        log.info("Resolved {} of {} referenced loan numbers", byLoanNumber.size(), loanNumbers.size());
        return byLoanNumber;
    }

    private Map<String, User> prefetchAgentsByEmails(Set<String> emails) {
        if (emails.isEmpty()) return Map.of();

        return userRepository.findByEmailIgnoreCaseIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(u -> u.getEmail().toLowerCase(), u -> u, (a, b) -> a));
    }

    private Map<String, String> buildHeaderMappings(Set<String> fileHeaders, List<ColumnSchema> schemas) {
        Map<String, String> mappings = new HashMap<>();
        for (ColumnSchema schema : schemas) {
            String schemaName = schema.getName().toLowerCase().replace("_", "").replace(" ", "");
            for (String header : fileHeaders) {
                String normalizedHeader = header.toLowerCase().replace("_", "").replace(" ", "");
                if (normalizedHeader.equals(schemaName)
                        || normalizedHeader.contains(schemaName)
                        || schemaName.contains(normalizedHeader)) {
                    mappings.put(schema.getName(), header);
                    break;
                }
            }
        }
        for (String header : fileHeaders) {
            mappings.putIfAbsent(header, header);
        }
        return mappings;
    }

    private List<FileProcessingError> validateRowDynamic(Map<String, String> rowData,
                                                        List<ColumnSchema> columnSchemas,
                                                        Map<String, String> headerMappings,
                                                        FileUpload fileUpload,
                                                        int rowNumber) {
        List<FileProcessingError> errors = new ArrayList<>();
        for (ColumnSchema schema : columnSchemas) {
            if (Boolean.TRUE.equals(schema.getIsRequired())) {
                String mappedHeader = headerMappings.get(schema.getName());
                if (mappedHeader == null) {
                    errors.add(buildError(fileUpload, rowNumber, schema.getName(),
                            "Required column '" + schema.getDisplayName() + "' not found in file", null));
                } else {
                    String value = rowData.get(mappedHeader);
                    if (value == null || value.isBlank()) {
                        errors.add(buildError(fileUpload, rowNumber, schema.getName(),
                                schema.getDisplayName() + " is required", value));
                    }
                }
            }
        }
        return errors;
    }

    @Transactional
    public void updateProgress(UUID fileUploadId, int processedRows, int successfulRows,
                               int failedRows, FileUploadStatus status) {
        fileUploadRepository.updateProgress(fileUploadId, processedRows, successfulRows, failedRows, status);
    }

    private FileProcessingError buildError(FileUpload fileUpload, int rowNumber,
                                           String columnName, String errorMessage, String rawValue) {
        return FileProcessingError.builder()
                .fileUpload(fileUpload)
                .rowNumber(rowNumber)
                .columnName(columnName)
                .errorMessage(errorMessage)
                .rawValue(rawValue)
                .build();
    }

    private static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] content;
        private final String originalFilename;
        private final String contentType;

        ByteArrayMultipartFile(byte[] content, String originalFilename, String contentType) {
            this.content = content;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content == null || content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(java.io.File dest) throws IOException {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) { fos.write(content); }
        }
    }
}
