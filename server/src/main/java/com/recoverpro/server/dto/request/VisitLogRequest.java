package com.recoverpro.server.dto.request;

import com.recoverpro.server.enums.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitLogRequest {

    @NotNull
    private UUID allocationId;

    private UUID assignmentId;
    private UUID organizationId;

    @NotNull
    private LocalDate visitDate;

    private Contactability contactability;
    private String contactPerson;
    private String contactNumber;

    private Disp disp;
    private LocalDate nextVisitDate;
    private String rescheduleReason;

    private ClassificationCode classificationCode;
    private ReasonForDefault reasonForDefault;

    private BigDecimal amountCollected;
    private String paymentMode;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private Double gpsAccuracy;
    private Double gpsAltitude;
    private String gpsAddress;
    private Boolean mockLocationDetected;

    private ResidenceStatus residenceStatus;
    private OfficeStatus officeStatus;
    private String customerProfile;
    private String fieldUpdateFeedback;
    private String lastVisitedAddress;

    private String visitNotes;
    private String internalRemarks;
    private String afterHoursOverrideReason;

    /** Set only by the Lucien visit-interview tool, never by the manual form. */
    private String lucienSessionId;
}
