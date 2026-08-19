package com.recoverpro.server.entity;

import com.recoverpro.server.enums.OrganizationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    // SYSTEM 18 TASK 18.2.c: soft-delete marker. A non-null value starts the retention window --
    // OrganizationPurgeJob hard-purges the row (and, per RETENTION, everything scoped to it) once
    // the window elapses. Deliberately separate from isActive: a suspended-but-not-deleted org is
    // still fully recoverable with one PATCH; a deleted org is on a one-way countdown.
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deletion_reason", length = 500)
    private String deletionReason;

    // SYSTEM 18 TASK 18.2.c: set by OrganizationPurgeJob once the retention window elapses.
    // Distinct from deletedAt: a soft-deleted-but-not-yet-purged org is still fully restorable
    // with one PATCH; once purgedAt is set the row has been tombstoned (name/code/contact info
    // scrubbed and its subscription/feature-flag rows gone) and cannot be restored -- see that
    // job's javadoc for why the ROW itself still exists rather than being hard-deleted outright
    // (the audit trail's immutability trigger makes an actual DELETE of a referenced org
    // impossible by design, not by oversight).
    @Column(name = "purged_at")
    private Instant purgedAt;

    /** SYSTEM 08 TASK 8.3: org-admin-controlled MFA enforcement, independent of the platform-wide
     *  role-based enforcement ({@code app.security.mfa.enforce}/{@code MfaServiceImpl}) -- an org
     *  opting itself into MFA is a tenant decision, not gated by an unrelated platform config
     *  flag. See {@code MfaServiceImpl#requiresMfaEnrollment}. */
    @Column(name = "mfa_required", nullable = false)
    @Builder.Default
    private boolean mfaRequired = false;

    @Column(name = "lookup_hash_pepper", length = 64)
    private String lookupHashPepper;

    @Enumerated(EnumType.STRING)
    @Column(name = "org_type", nullable = false, length = 30)
    private OrganizationType organizationType;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
