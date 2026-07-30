package com.recoverpro.server.mapper;

import com.recoverpro.server.dto.request.VisitLogRequest;
import com.recoverpro.server.dto.response.VisitLogResponse;
import com.recoverpro.server.entity.VisitLog;
import com.recoverpro.server.enums.ApprovalStatus;
import com.recoverpro.server.enums.VisitStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class VisitLogMapper {

    public VisitLog toEntity(VisitLogRequest request, UUID agentId, UUID createdBy) {
        return VisitLog.builder()
                .allocationId(request.getAllocationId())
                .assignmentId(request.getAssignmentId())
                .agentId(agentId)
                .organizationId(request.getOrganizationId())
                .visitDate(request.getVisitDate())
                .visitTime(Instant.now())
                .contactability(request.getContactability())
                .contactPerson(request.getContactPerson())
                .contactNumber(request.getContactNumber())
                .disp(request.getDisp())
                .nextVisitDate(request.getNextVisitDate())
                .rescheduleReason(request.getRescheduleReason())
                .classificationCode(request.getClassificationCode())
                .reasonForDefault(request.getReasonForDefault())
                .approvalStatus(ApprovalStatus.PENDING)
                .amountCollected(request.getAmountCollected())
                .paymentMode(request.getPaymentMode())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .gpsAccuracy(request.getGpsAccuracy())
                .gpsAltitude(request.getGpsAltitude())
                .gpsCapturedAt(Instant.now())
                .gpsAddress(request.getGpsAddress())
                .visitStatus(VisitStatus.GPS_UNAVAILABLE)
                .mockLocationDetected(Boolean.TRUE.equals(request.getMockLocationDetected()))
                .residenceStatus(request.getResidenceStatus())
                .officeStatus(request.getOfficeStatus())
                .customerProfile(request.getCustomerProfile())
                .fieldUpdateFeedback(request.getFieldUpdateFeedback())
                .lastVisitedAddress(request.getLastVisitedAddress())
                .visitNotes(request.getVisitNotes())
                .internalRemarks(request.getInternalRemarks())
                .lucienSessionId(request.getLucienSessionId())
                .createdBy(createdBy)
                .isDeleted(false)
                .build();
    }

    public VisitLogResponse toResponse(VisitLog entity) {
        List<VisitLogResponse.VisitImageResponse> imageResponses =
                entity.getImages() == null
                        ? Collections.emptyList()
                        : entity.getImages().stream()
                        .filter(img -> !Boolean.TRUE.equals(img.getIsDeleted()))
                        .sorted((a, b) -> Integer.compare(
                                a.getSequenceNumber() == null ? 0 : a.getSequenceNumber(),
                                b.getSequenceNumber() == null ? 0 : b.getSequenceNumber()))
                        .map(img -> VisitLogResponse.VisitImageResponse.builder()
                                .id(img.getId())
                                .sequenceNumber(img.getSequenceNumber())
                                .imagePath(img.getImagePath())
                                .s3Key(img.getS3Key())
                                .originalFilename(img.getOriginalFilename())
                                .uploadStatus(img.getUploadStatus())
                                .build())
                        .collect(Collectors.toList());

        return VisitLogResponse.builder()
                .id(entity.getId())
                .allocationId(entity.getAllocationId())
                .assignmentId(entity.getAssignmentId())
                .agentId(entity.getAgentId())
                .organizationId(entity.getOrganizationId())
                .loanNumber(entity.getLoanNumber())
                .visitDate(entity.getVisitDate())
                .visitTime(entity.getVisitTime())
                .contactability(entity.getContactability())
                .contactPerson(entity.getContactPerson())
                .contactNumber(entity.getContactNumber())
                .disp(entity.getDisp())
                .visitOutcome(entity.getVisitOutcome())
                .nextVisitDate(entity.getNextVisitDate())
                .rescheduleReason(entity.getRescheduleReason())
                .classificationCode(entity.getClassificationCode())
                .reasonForDefault(entity.getReasonForDefault())
                .approvalStatus(entity.getApprovalStatus())
                .approvalRemarks(entity.getApprovalRemarks())
                .approvedBy(entity.getApprovedBy())
                .approvedAt(entity.getApprovedAt())
                .collectionId(entity.getCollectionId())
                .ptpId(entity.getPtpId())
                .amountCollected(entity.getAmountCollected())
                .paymentMode(entity.getPaymentMode())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .gpsAccuracy(entity.getGpsAccuracy())
                .gpsAddress(entity.getGpsAddress())
                .visitStatus(entity.getVisitStatus())
                .mockLocationDetected(entity.getMockLocationDetected())
                .residenceStatus(entity.getResidenceStatus())
                .officeStatus(entity.getOfficeStatus())
                .customerProfile(entity.getCustomerProfile())
                .fieldUpdateFeedback(entity.getFieldUpdateFeedback())
                .lastVisitedAddress(entity.getLastVisitedAddress())
                .images(imageResponses)
                .visitNotes(entity.getVisitNotes())
                .internalRemarks(entity.getInternalRemarks())
                .lucienSessionId(entity.getLucienSessionId())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
