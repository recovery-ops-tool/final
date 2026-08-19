package com.recoverpro.server.entity;

import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.AuditSeverity;
import com.recoverpro.server.enums.AuditSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Unified audit surface for everything that previously had no home: auth, RBAC, platform-admin
 * actions, billing, exports, and bulk-import lifecycle stages. Does NOT replace the five existing
 * per-domain audit tables (assignment/collection/ptp/settlement/allocation_audit_logs) -- those
 * keep writing their own narrower rows for case-timeline UIs.
 * <p>
 * Table is named unified_audit_events, not audit_events: this database already has an unrelated,
 * orphaned table literally named audit_events (pre-existing, no Flyway migration owns it, no other
 * code references it -- see V085's migration comment) that a same-named table here would collide
 * with.
 * <p>
 * Maps only {@code id} as the JPA identifier even though the DB primary key is the composite
 * {@code (id, created_at)} required by range partitioning -- same simplification already used by
 * {@link UserActionAuditLog} against its own partitioned table; {@code id} is globally unique via
 * {@code gen_random_uuid()} so ordinary CRUD by id needs no composite key mapping.
 */
@Entity
@Table(name = "unified_audit_events", indexes = {
        @Index(name = "idx_unified_audit_events_org_time",   columnList = "organization_id, created_at"),
        @Index(name = "idx_unified_audit_events_org_actor",  columnList = "organization_id, actor_user_id, created_at"),
        @Index(name = "idx_unified_audit_events_org_action", columnList = "organization_id, action, created_at"),
        @Index(name = "idx_unified_audit_events_resource",   columnList = "organization_id, resource_type, resource_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Null only for true platform-global events (no tenant involved), per the V080 precedent
     *  for platform-admin users. Never populated from caller input -- see AuditService. */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private AuditActorType actorType;

    /** Snapshot of the role name(s) active at the time of the action (free text, not a fixed
     *  enum) -- matches RecoverPro's DB-driven, org-customizable Role entity rather than a rival
     *  closed role list. Comma-joined if the actor holds more than one role. 500, not 100 (V108):
     *  a single role fits comfortably under 100, but the column was originally sized before
     *  accounting for a principal ever holding several roles at once, which this schema already
     *  permits. */
    @Column(name = "actor_role", length = 500)
    private String actorRole;

    /** Defaults to actorUserId. Exists so a future impersonation feature has somewhere to record
     *  "who it actually happened to" without a schema change. */
    @Column(name = "effective_user_id")
    private UUID effectiveUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    private AuditResourceType resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private AuditSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 10)
    private AuditResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private AuditSource source;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
