package com.recoverpro.server.service.impl;

import com.recoverpro.server.client.ClamAvScannerClient;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.VisitApprovalRequest;
import com.recoverpro.server.dto.request.VisitLogRequest;
import com.recoverpro.server.dto.response.VisitLogResponse;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.AllocationAuditLog;
import com.recoverpro.server.entity.VisitImage;
import com.recoverpro.server.entity.VisitLog;
import com.recoverpro.server.enums.ApprovalAction;
import com.recoverpro.server.enums.ApprovalStatus;
import com.recoverpro.server.enums.VisitStatus;
import com.recoverpro.server.mapper.VisitLogMapper;
import com.recoverpro.server.repository.AllocationAuditLogRepository;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.repository.VisitImageRepository;
import com.recoverpro.server.repository.VisitLogRepository;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.enums.GuardType;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.CallingHoursGuard;
import com.recoverpro.server.service.VisitLogService;
import com.recoverpro.server.service.compliance.ComplianceAuditService;
import com.recoverpro.server.service.storage.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitLogServiceImpl implements VisitLogService {

    private final VisitLogRepository visitLogRepository;
    private final VisitLogMapper visitLogMapper;
    private final AllocationRepository allocationRepository;
    private final AllocationAuditLogRepository allocationAuditLogRepository;
    private final UserRepository userRepository;
    private final VisitImageRepository visitImageRepository;
    private final CallingHoursGuard callingHoursGuard;
    private final UserActionAuditService auditLogService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ClamAvScannerClient clamAvScannerClient;
    private final ComplianceAuditService complianceAuditService;
    private final StoragePort storagePort;

    @Value("${app.storage.visits-path:./uploads/visits}")
    private String storagePath;

    @Value("${app.visit.gps-mismatch-threshold-metres:500}")
    private double gpsMismatchThresholdMetres;

    @Value("${aws.s3.signed-url-duration-hours:24}")
    private long signedUrlDurationHours;

    // ─── CREATE ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VisitLogResponse create(VisitLogRequest request,
                                   MultipartFile image1,
                                   MultipartFile image2,
                                   MultipartFile image3,
                                   UUID agentId,
                                   UUID createdBy) {

        log.info("Creating visit log: allocation={} agent={}", request.getAllocationId(), agentId);

        if (image1 == null || image1.isEmpty()) {
            throw new BusinessException("First visit photo (image1) is required");
        }

        if (false && Boolean.TRUE.equals(request.getMockLocationDetected())) {
            auditLogService.logUserAction(agentId, "MOCK_LOCATION_VISIT_REJECTED",
                    String.format("allocation=%s org=%s", request.getAllocationId(), request.getOrganizationId()));
            log.warn("Mock-location visit rejected: allocation={} agent={}", request.getAllocationId(), agentId);
            throw new BusinessException(
                    "Visit rejected: mock/fake GPS provider detected. Use a real device location.");
        }

        ZonedDateTime visitMoment = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        String windowDenial = callingHoursGuard.denialReason(request.getOrganizationId(), visitMoment);
        if (windowDenial != null) {
            String override = request.getAfterHoursOverrideReason();
            if (override == null || override.isBlank()) {
                String reason = "Visit outside RBI calling-hours window (" + windowDenial
                        + "). A supervisor override reason is required to log this visit.";
                complianceAuditService.record(GuardType.CALLING_HOURS, request.getAllocationId(),
                        request.getOrganizationId(), agentId, "VISIT_LOG_CREATE", reason);
                throw new BusinessException(reason);
            }
            String tag = "[after-hours override @" + visitMoment + " | " + windowDenial + "] " + override;
            request.setInternalRemarks(
                    request.getInternalRemarks() == null ? tag : tag + "\n" + request.getInternalRemarks());
            log.warn("After-hours visit logged for allocation {} agent {}: {}",
                    request.getAllocationId(), agentId, override);
        }

        Allocation allocation = allocationRepository
                .findByIdAndIsDeletedFalse(request.getAllocationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Allocation", request.getAllocationId()));

        VisitLog visitLog = visitLogMapper.toEntity(request, agentId, createdBy);
        visitLog.setLoanNumber(allocation.getLoanNumber());
        resolveGpsStatus(visitLog, request);

        attachImage(visitLog, image1, 1, createdBy);
        if (image2 != null && !image2.isEmpty()) attachImage(visitLog, image2, 2, createdBy);
        if (image3 != null && !image3.isEmpty()) attachImage(visitLog, image3, 3, createdBy);

        VisitLog saved = visitLogRepository.save(visitLog);
        log.info("Visit log created: id={} disp={} gpsStatus={}", saved.getId(), saved.getDisp(), saved.getVisitStatus());

        if (saved.getDisp() != null) {
            var previousDisp = allocation.getLatestDisposition();
            allocation.setLatestDisposition(saved.getDisp());
            allocationRepository.save(allocation);

            if (previousDisp != saved.getDisp()) {
                allocationAuditLogRepository.save(AllocationAuditLog.builder()
                        .allocationId(allocation.getId())
                        .action("DISPOSITION_CHANGED")
                        .performedBy(agentId)
                        .previousValue(previousDisp == null ? null : previousDisp.name())
                        .newValue(saved.getDisp().name())
                        .reason("Set from visit log")
                        .build());
            }
        }

        return visitLogMapper.toResponse(saved);
    }

    // ─── READS ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public VisitLogResponse getById(UUID id) {
        return enrichWithNames(visitLogMapper.toResponse(requireVisitLog(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitLogResponse> getByAllocationId(UUID allocationId) {
        return visitLogRepository.findByAllocationIdAndIsDeletedFalse(allocationId)
                .stream().map(visitLogMapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitLogResponse> getByAgentId(UUID agentId) {
        return visitLogRepository.findByAgentIdAndIsDeletedFalse(agentId)
                .stream().map(visitLogMapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VisitLogResponse> getByAgentIdPaged(UUID agentId, Pageable pageable) {
        return visitLogRepository.findByAgentIdAndIsDeletedFalse(agentId, pageable)
                .map(v -> enrichWithNames(visitLogMapper.toResponse(v)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VisitLogResponse> getByOrganizationIdPaged(UUID orgId, Pageable pageable) {
        return visitLogRepository.findByOrganizationIdAndIsDeletedFalse(orgId, pageable)
                .map(v -> enrichWithNames(visitLogMapper.toResponse(v)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitLogResponse> getTodayVisits(UUID agentId) {
        return visitLogRepository
                .findByAgentIdAndVisitDateAndIsDeletedFalse(agentId, LocalDate.now())
                .stream().map(visitLogMapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public String getAgentDisplayName(UUID agentId) {
        return userRepository.findById(agentId)
                .map(u -> {
                    String last = u.getLastName();
                    String init = (last != null && !last.isEmpty()) ? last.substring(0, 1) + "." : "";
                    return u.getFirstName() + " " + init;
                })
                .orElse("Unknown Agent");
    }

    // ─── APPROVAL ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VisitLogResponse approveVisit(UUID visitId, VisitApprovalRequest request, UUID approvedBy) {
        VisitLog visitLog = requireVisitLog(visitId);

        if (visitLog.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(
                    "Visit is already " + visitLog.getApprovalStatus() + " and cannot be actioned again");
        }
        if (approvedBy.equals(visitLog.getAgentId())) {
            throw new BusinessException(
                    "Maker-checker violation: the agent who created a visit cannot approve it");
        }

        ApprovalStatus newStatus = switch (request.getAction()) {
            case APPROVE -> ApprovalStatus.APPROVED;
            case REJECT  -> ApprovalStatus.REJECTED;
            default -> throw new BusinessException("Invalid approval action: " + request.getAction());
        };

        if (newStatus == ApprovalStatus.REJECTED
                && (request.getRemarks() == null || request.getRemarks().isBlank())) {
            throw new BusinessException("Rejection reason (remarks) is mandatory");
        }

        visitLog.setApprovalStatus(newStatus);
        visitLog.setApprovalRemarks(request.getRemarks());
        visitLog.setApprovedBy(approvedBy);
        visitLog.setApprovedAt(Instant.now());
        visitLog.setUpdatedBy(approvedBy);

        VisitLog saved = visitLogRepository.save(visitLog);
        log.info("Visit {} {} by {}", visitId, newStatus, approvedBy);
        return visitLogMapper.toResponse(saved);
    }

    // ─── LINK OPERATIONS ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public VisitLogResponse linkCollection(UUID visitId, UUID collectionId) {
        VisitLog visitLog = requireVisitLog(visitId);
        if (visitLog.getPtpId() != null) {
            throw new BusinessException("Cannot link collection: Visit already has a PTP linked.");
        }
        visitLog.setCollectionId(collectionId);
        visitLog.setVisitOutcome("COLLECTED");
        VisitLog saved = visitLogRepository.save(visitLog);
        log.info("Linked collection {} to visit {}", collectionId, visitId);
        return visitLogMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public VisitLogResponse linkPtp(UUID visitId, UUID ptpId) {
        VisitLog visitLog = requireVisitLog(visitId);
        if (visitLog.getCollectionId() != null) {
            throw new BusinessException("Cannot link PTP: Visit already has a Collection linked.");
        }
        visitLog.setPtpId(ptpId);
        visitLog.setVisitOutcome("PTP_MADE");
        VisitLog saved = visitLogRepository.save(visitLog);
        log.info("Linked PTP {} to visit {}", ptpId, visitId);
        return visitLogMapper.toResponse(saved);
    }

    // ─── LAST VISITED ADDRESS ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getLastVisitedAddress(UUID allocationId) {
        return visitLogRepository.findLastVisitedAddress(allocationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getLastLocation(UUID allocationId) {
        return visitLogRepository.findLastLocationVisit(allocationId).map(v -> {
            Map<String, Object> loc = new HashMap<>();
            loc.put("lat", v.getLatitude());
            loc.put("lng", v.getLongitude());
            if (v.getGpsAddress() != null) loc.put("address", v.getGpsAddress());
            return loc;
        });
    }

    // ─── DELETE ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void softDelete(UUID id, UUID deletedBy) {
        VisitLog visitLog = requireVisitLog(id);
        visitLog.setIsDeleted(true);
        visitLog.setCollectionId(null);
        visitLog.setPtpId(null);
        visitLog.setUpdatedBy(deletedBy);
        visitLogRepository.save(visitLog);
        log.info("Soft deleted visit log: {} (collection/ptp links detached)", id);
    }

    // ─── SIGNED URL ──────────────────────────────────────────────────────────

    @Override
    public String regenerateSignedUrl(UUID visitId, int imageSequence) {
        VisitImage img = visitImageRepository
                .findByVisitLogIdAndSequenceNumberAndIsDeletedFalse(visitId, imageSequence)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No image at sequence " + imageSequence + " for visit " + visitId));

        if (img.getS3Key() != null && !img.getS3Key().isBlank()) {
            try {
                String url = storagePort.presignedUrl(img.getS3Key(), Duration.ofHours(signedUrlDurationHours));
                if (url != null) return url;
            } catch (Exception e) {
                log.error("Failed to generate presigned URL for visit {} seq {}: {}",
                        visitId, imageSequence, e.getMessage());
                throw new ResourceNotFoundException("Could not generate image URL");
            }
        }

        String filePath = img.getImagePath();
        if (filePath == null) {
            throw new ResourceNotFoundException("Image path not stored for sequence " + imageSequence);
        }
        try {
            byte[] bytes = storagePort.readBytes(filePath);
            String mime = img.getContentType() != null ? img.getContentType() : "image/jpeg";
            return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.error("Failed to read image file {} for visit {}: {}", filePath, visitId, e.getMessage());
            throw new ResourceNotFoundException("Image file not found on server");
        }
    }

    // ─── INTERNAL ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public VisitLog findVisitById(UUID id) {
        return requireVisitLog(id);
    }

    // ─── PRIVATE HELPERS ─────────────────────────────────────────────────────

    private VisitLog requireVisitLog(UUID id) {
        VisitLog visit = visitLogRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("VisitLog", id));
        if (!orgIsolationGuard.belongsToOrg(visit.getOrganizationId())) {
            throw new ResourceNotFoundException("VisitLog", id);
        }
        return visit;
    }

    private void resolveGpsStatus(VisitLog visitLog, VisitLogRequest request) {
        if (Boolean.TRUE.equals(visitLog.getMockLocationDetected())) {
            visitLog.setVisitStatus(VisitStatus.MOCK_LOCATION_DETECTED);
            return;
        }
        Double lat = request.getLatitude();
        Double lng = request.getLongitude();
        if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) {
            visitLog.setLatitude(null);
            visitLog.setLongitude(null);
            visitLog.setVisitStatus(VisitStatus.GPS_UNAVAILABLE);
            return;
        }
        Double dist = visitLog.getDistanceFromExpected();
        if (dist != null) {
            visitLog.setVisitStatus(
                    dist > gpsMismatchThresholdMetres ? VisitStatus.GPS_MISMATCH : VisitStatus.VERIFIED);
        } else {
            visitLog.setVisitStatus(VisitStatus.NOT_VERIFIED);
        }
    }

    private void attachImage(VisitLog visitLog, MultipartFile file, int sequenceNumber, UUID uploadedBy) {
        VisitImage img = VisitImage.builder()
                .visitLog(visitLog)
                .sequenceNumber(sequenceNumber)
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .uploadedBy(uploadedBy)
                .isDeleted(false)
                .build();

        try {
            if (!clamAvScannerClient.isClean(file.getInputStream())) {
                log.warn("Image seq={} for visit rejected by virus scan", sequenceNumber);
                img.setUploadStatus("REJECTED_INFECTED");
                visitLog.getImages().add(img);
                return;
            }

            UUID refId = visitLog.getId() != null ? visitLog.getId() : UUID.randomUUID();
            String safeName = sanitize(file.getOriginalFilename());
            String filename = refId + "_seq" + sequenceNumber + "_" + System.currentTimeMillis() + "_" + safeName;
            UUID orgId = visitLog.getOrganizationId();
            String s3Key = "visits/" + (orgId != null ? orgId : "unknown")
                    + "/" + refId + "/seq" + sequenceNumber
                    + "_" + System.currentTimeMillis() + "_" + safeName;
            Path localPath = Paths.get(storagePath, filename);

            String stored = storagePort.store(s3Key, localPath, file.getInputStream(),
                    file.getContentType() != null ? file.getContentType() : "image/jpeg", file.getSize());
            if (storagePort.isS3Enabled()) {
                img.setS3Key(stored);
            } else {
                img.setImagePath(stored);
            }
            img.setUploadStatus("UPLOADED");
            log.info("Image seq={} for visit {} stored at {}", sequenceNumber, refId, stored);
        } catch (Exception e) {
            log.error("Failed to save image #{} for visit: {}", sequenceNumber, e.getMessage(), e);
            img.setUploadStatus("FAILED");
        }

        visitLog.getImages().add(img);
    }

    private VisitLogResponse enrichWithNames(VisitLogResponse r) {
        if (r.getAgentId() != null) r.setAgentName(getAgentDisplayName(r.getAgentId()));
        if (r.getAllocationId() != null) {
            allocationRepository.findById(r.getAllocationId())
                    .ifPresent(a -> r.setBorrowerName(a.getBorrowerName()));
        }
        return r;
    }

    private static String sanitize(String filename) {
        if (filename == null) return "photo";
        return filename.replaceAll("[/\\\\]", "_");
    }
}
