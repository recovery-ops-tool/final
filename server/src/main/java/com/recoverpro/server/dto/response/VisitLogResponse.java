package com.recoverpro.server.dto.response;

import com.recoverpro.server.enums.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitLogResponse {

    private UUID id;
    private UUID allocationId;
    private UUID assignmentId;
    private UUID agentId;
    private String agentName;
    private UUID organizationId;
    private String loanNumber;
    private String borrowerName;

    private LocalDate visitDate;
    private Instant visitTime;

    private Contactability contactability;
    private String contactPerson;
    private String contactNumber;

    private Disp disp;
    private String visitOutcome;
    private LocalDate nextVisitDate;
    private String rescheduleReason;

    private ClassificationCode classificationCode;
    private ReasonForDefault reasonForDefault;

    private ApprovalStatus approvalStatus;
    private String approvalRemarks;
    private UUID approvedBy;
    private Instant approvedAt;

    private UUID collectionId;
    private UUID ptpId;
    private BigDecimal amountCollected;
    private String paymentMode;

    private Double latitude;
    private Double longitude;
    private Double gpsAccuracy;
    private String gpsAddress;
    private VisitStatus visitStatus;
    private Boolean mockLocationDetected;

    private ResidenceStatus residenceStatus;
    private OfficeStatus officeStatus;
    private String customerProfile;
    private String fieldUpdateFeedback;
    private String lastVisitedAddress;

    private List<VisitImageResponse> images;

    private String visitNotes;
    private String internalRemarks;
    private String lucienSessionId;

    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VisitImageResponse {
        private UUID id;
        private Integer sequenceNumber;
        private String imagePath;
        private String s3Key;
        private String originalFilename;
        private String uploadStatus;
    }
}
