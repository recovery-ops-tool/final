package com.recoverpro.server.entity;

import com.recoverpro.server.enums.*;
import com.recoverpro.server.security.encryption.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "visit_logs", indexes = {
        @Index(name = "idx_visit_allocation_id",  columnList = "allocation_id"),
        @Index(name = "idx_visit_agent_id",        columnList = "agent_id"),
        @Index(name = "idx_visit_date",            columnList = "visit_date"),
        @Index(name = "idx_visit_status",          columnList = "visit_status"),
        @Index(name = "idx_visit_contactability",  columnList = "contactability"),
        @Index(name = "idx_visit_disp",            columnList = "disp"),
        @Index(name = "idx_visit_approval_status", columnList = "approval_status"),
        @Index(name = "idx_visit_org_loan_date",   columnList = "organization_id, loan_number, visit_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "allocation_id", nullable = false)
    private UUID allocationId;

    @Column(name = "loan_number", nullable = false, length = 100)
    private String loanNumber;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "visit_time")
    private Instant visitTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "contactability", length = 50)
    private Contactability contactability;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_person", length = 512)
    private String contactPerson;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_number", length = 256)
    private String contactNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "disp", length = 30)
    private Disp disp;

    @Column(name = "visit_outcome", length = 30)
    private String visitOutcome;

    @Column(name = "next_visit_date")
    private LocalDate nextVisitDate;

    @Column(name = "reschedule_reason", columnDefinition = "TEXT")
    private String rescheduleReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_code", length = 60)
    private ClassificationCode classificationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_for_default", length = 50)
    private ReasonForDefault reasonForDefault;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20, nullable = false)
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(name = "approval_remarks", columnDefinition = "TEXT")
    private String approvalRemarks;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "collection_id")
    private UUID collectionId;

    @Column(name = "ptp_id")
    private UUID ptpId;

    @Column(name = "amount_collected", precision = 15, scale = 2)
    private BigDecimal amountCollected;

    @Column(name = "payment_mode", length = 20)
    private String paymentMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "residence_status", length = 60)
    private ResidenceStatus residenceStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "office_status", length = 60)
    private OfficeStatus officeStatus;

    @Column(name = "customer_profile", length = 100)
    private String customerProfile;

    @Column(name = "field_update_feedback", columnDefinition = "TEXT")
    private String fieldUpdateFeedback;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "last_visited_address", columnDefinition = "TEXT")
    private String lastVisitedAddress;

    @OneToMany(mappedBy = "visitLog", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VisitImage> images = new ArrayList<>();

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "visit_notes", columnDefinition = "TEXT")
    private String visitNotes;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "internal_remarks", columnDefinition = "TEXT")
    private String internalRemarks;

    /** Set when this visit was captured via a Lucien visit-interview conversation, rather than
     * the manual form - traces the record back to its full chat transcript for audit. Loose FK
     * by convention (ChatSession.id, same pattern as lucien_agent_steps.session_id). */
    @Column(name = "lucien_session_id", length = 36)
    private String lucienSessionId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "gps_accuracy")
    private Double gpsAccuracy;

    @Column(name = "gps_altitude")
    private Double gpsAltitude;

    @Column(name = "gps_captured_at")
    private Instant gpsCapturedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "gps_address", columnDefinition = "TEXT")
    private String gpsAddress;

    @Column(name = "distance_from_expected")
    private Double distanceFromExpected;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_status", length = 30, nullable = false)
    @Builder.Default
    private VisitStatus visitStatus = VisitStatus.GPS_UNAVAILABLE;

    @Column(name = "mock_location_detected", nullable = false)
    @Builder.Default
    private Boolean mockLocationDetected = false;
}
