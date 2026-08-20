package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.LogMask;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.CompleteCallRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.CallLogResponse;
import com.recoverpro.server.dto.response.CallStartResponse;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.Borrower;
import com.recoverpro.server.entity.CallLog;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.CallOutcome;
import com.recoverpro.server.enums.RecordingStatus;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.BorrowerRepository;
import com.recoverpro.server.repository.CallLogRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.CallLogService;
import com.recoverpro.server.service.storage.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallLogServiceImpl implements CallLogService {

    private static final long MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac"
    );

    private final CallLogRepository callLogRepository;
    private final AllocationService allocationService;
    private final AllocationRepository allocationRepository;
    private final BorrowerRepository borrowerRepository;
    private final UserRepository userRepository;
    private final StoragePort storagePort;
    private final CallLogFailureRecorder callLogFailureRecorder;
    private final AuditService auditService;

    @Value("${app.storage.call-recordings-path:./uploads/call-recordings}")
    private String storagePath;

    @Value("${aws.s3.signed-url-duration-hours:24}")
    private long signedUrlDurationHours;

    @Override
    @Transactional
    public CallStartResponse startCall(UUID allocationId, UUID agentId, UUID organizationId) {
        AllocationResponse allocation = requireAllocationInOrg(allocationId, organizationId);

        Borrower borrower = borrowerRepository.findById(allocation.getBorrowerId())
                .orElseThrow(() -> new ResourceNotFoundException("Borrower", allocation.getBorrowerId()));
        String phone = borrower.getPhone();
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("This borrower has no phone number on file.");
        }

        CallLog callLog = CallLog.builder()
                .organizationId(organizationId)
                .allocationId(allocationId)
                .agentId(agentId)
                .initiatedAt(Instant.now())
                .phoneMasked(LogMask.phone(phone))
                .recordingStatus(RecordingStatus.PENDING)
                .build();
        CallLog saved = callLogRepository.save(callLog);
        log.info("Call started: id={} allocation={} agent={}", saved.getId(), allocationId, agentId);
        auditCallLog(saved.getId(), AuditAction.CALL_STARTED, AuditResult.SUCCESS, null,
                Map.of("allocationId", allocationId.toString()));

        return CallStartResponse.builder()
                .callLogId(saved.getId())
                .phoneNumber(phone)
                .build();
    }

    @Override
    @Transactional
    public void attachRecording(UUID callLogId, UUID organizationId, MultipartFile file) {
        CallLog callLog = requireCallLogInOrg(callLogId, organizationId);
        validateFile(file);

        String filename = callLogId + ".m4a";
        String s3Key = "call-recordings/" + organizationId + "/" + callLog.getAllocationId() + "/" + filename;
        Path localPath = Paths.get(storagePath, organizationId.toString(),
                callLog.getAllocationId().toString()).resolve(filename);

        String storedPath;
        try {
            storedPath = storagePort.store(s3Key, localPath, file.getInputStream(), file.getContentType(), file.getSize());
        } catch (Exception e) {
            log.error("Failed to store call recording for callLog {}: {}", callLogId, e.getMessage(), e);
            callLogFailureRecorder.markFailed(callLogId);
            auditCallLog(callLogId, AuditAction.CALL_RECORDING_UPLOADED, AuditResult.FAILURE, e.getMessage(), null);
            throw new BusinessException("Failed to store call recording: " + e.getMessage());
        }

        callLog.setRecordingPath(storedPath);
        callLog.setRecordingStatus(RecordingStatus.UPLOADED);
        callLogRepository.save(callLog);
        log.info("Call recording stored: callLogId={} location={}", callLogId, storedPath);
        auditCallLog(callLogId, AuditAction.CALL_RECORDING_UPLOADED, AuditResult.SUCCESS, null, null);
    }

    @Override
    @Transactional
    public CallLogResponse completeCall(UUID callLogId, UUID organizationId, CompleteCallRequest request) {
        CallLog callLog = requireCallLogInOrg(callLogId, organizationId);
        callLog.setEndedAt(Instant.now());
        callLog.setOutcome(request.getOutcome());
        callLog.setNotes(request.getNotes());
        callLog.setDurationSeconds(request.getDurationSeconds());
        if (callLog.getRecordingStatus() == RecordingStatus.PENDING) {
            callLog.setRecordingStatus(RecordingStatus.NOT_RECORDED);
        }
        CallLog saved = callLogRepository.save(callLog);
        log.info("Call completed: id={} outcome={}", callLogId, request.getOutcome());
        auditCallLog(callLogId, AuditAction.CALL_COMPLETED, AuditResult.SUCCESS, null,
                Map.of("outcome", String.valueOf(request.getOutcome())));
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CallLogResponse> getByAllocation(UUID allocationId, UUID organizationId) {
        requireAllocationInOrg(allocationId, organizationId);
        List<CallLog> callLogs = callLogRepository.findByAllocationIdOrderByInitiatedAtDesc(allocationId);
        if (callLogs.isEmpty()) return List.of();

        Set<UUID> agentIds = callLogs.stream().map(CallLog::getAgentId).collect(Collectors.toSet());
        Map<UUID, String> agentNames = new HashMap<>();
        userRepository.findAllById(agentIds).forEach(u -> agentNames.put(u.getId(), displayName(u)));

        return callLogs.stream()
                .map(c -> toResponse(c, agentNames.get(c.getAgentId()), null, null))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CallLogResponse> getForOrg(UUID organizationId, UUID agentId, CallOutcome outcome,
                                            Instant fromDate, Instant toDate, Pageable pageable) {
        Page<CallLog> page = callLogRepository.findWithFilters(organizationId, agentId, outcome, fromDate, toDate, pageable);
        List<CallLog> callLogs = page.getContent();
        if (callLogs.isEmpty()) return page.map(c -> toResponse(c, null, null, null));

        Set<UUID> agentIds = callLogs.stream().map(CallLog::getAgentId).collect(Collectors.toSet());
        Map<UUID, String> agentNames = new HashMap<>();
        userRepository.findAllById(agentIds).forEach(u -> agentNames.put(u.getId(), displayName(u)));

        Set<UUID> allocationIds = callLogs.stream().map(CallLog::getAllocationId).collect(Collectors.toSet());
        Map<UUID, Allocation> allocationsById = new HashMap<>();
        allocationRepository.findAllById(allocationIds).forEach(a -> allocationsById.put(a.getId(), a));

        return page.map(c -> {
            Allocation alloc = allocationsById.get(c.getAllocationId());
            return toResponse(c, agentNames.get(c.getAgentId()),
                    alloc != null ? alloc.getLoanNumber() : null,
                    alloc != null ? alloc.getBorrowerName() : null);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Resource downloadRecording(UUID callLogId, UUID organizationId) {
        CallLog callLog = requireCallLogInOrg(callLogId, organizationId);
        if (callLog.getRecordingStatus() != RecordingStatus.UPLOADED || callLog.getRecordingPath() == null) {
            throw new BusinessException("No recording is available for this call.");
        }

        auditCallLog(callLogId, AuditAction.CALL_RECORDING_ACCESSED, AuditResult.SUCCESS, null,
                Map.of("allocationId", callLog.getAllocationId().toString()));

        if (storagePort.isS3Enabled()) {
            String url = storagePort.presignedUrl(callLog.getRecordingPath(), Duration.ofHours(signedUrlDurationHours));
            try {
                return new UrlResource(url);
            } catch (MalformedURLException e) {
                throw new BusinessException("Could not generate URL for recording: " + callLogId);
            }
        }

        try {
            Path filePath = Paths.get(callLog.getRecordingPath());
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BusinessException("Recording file is not accessible: " + callLogId);
            }
            return resource;
        } catch (MalformedURLException e) {
            throw new BusinessException("Invalid recording path for call: " + callLogId);
        }
    }

    private AllocationResponse requireAllocationInOrg(UUID allocationId, UUID organizationId) {
        AllocationResponse allocation = allocationService.getAllocationById(allocationId);
        if (allocation.getOrganizationId() == null || !allocation.getOrganizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Allocation not found");
        }
        return allocation;
    }

    private CallLog requireCallLogInOrg(UUID callLogId, UUID organizationId) {
        CallLog callLog = callLogRepository.findById(callLogId)
                .orElseThrow(() -> new ResourceNotFoundException("Call log not found"));
        if (!callLog.getOrganizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Call log not found");
        }
        return callLog;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("Recording file must not be empty");
        if (file.getSize() > MAX_FILE_SIZE_BYTES)
            throw new BusinessException("Recording exceeds maximum allowed size of 20 MB");
        String ct = file.getContentType();
        if (ct == null || !ALLOWED_CONTENT_TYPES.contains(ct.toLowerCase()))
            throw new BusinessException("Unsupported recording file type: " + ct);
    }

    private CallLogResponse toResponse(CallLog callLog) {
        String agentName = userRepository.findById(callLog.getAgentId()).map(this::displayName).orElse(null);
        return toResponse(callLog, agentName, null, null);
    }

    private CallLogResponse toResponse(CallLog callLog, String agentName, String loanNumber, String borrowerName) {
        return CallLogResponse.builder()
                .id(callLog.getId())
                .allocationId(callLog.getAllocationId())
                .loanNumber(loanNumber)
                .borrowerName(borrowerName)
                .agentId(callLog.getAgentId())
                .agentName(agentName)
                .initiatedAt(callLog.getInitiatedAt())
                .endedAt(callLog.getEndedAt())
                .durationSeconds(callLog.getDurationSeconds())
                .outcome(callLog.getOutcome())
                .phoneMasked(callLog.getPhoneMasked())
                .notes(callLog.getNotes())
                .recordingStatus(callLog.getRecordingStatus())
                .build();
    }

    /** Actor/org resolve from live request context (SecurityContextHolder/RlsOrgIdHolder) --
     *  every call site here runs inside an authenticated, org-scoped controller call. */
    private void auditCallLog(UUID callLogId, AuditAction action, AuditResult result, String reason,
                               Map<String, Object> metadata) {
        auditService.record(AuditEventRequest.builder()
                .action(action)
                .resourceType(AuditResourceType.CALL_LOG)
                .resourceId(callLogId.toString())
                .result(result)
                .reason(reason)
                .metadata(metadata)
                .build());
    }

    private String displayName(User u) {
        String first = Objects.requireNonNullElse(u.getFirstName(), "").trim();
        String last = Objects.requireNonNullElse(u.getLastName(), "").trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }
}
