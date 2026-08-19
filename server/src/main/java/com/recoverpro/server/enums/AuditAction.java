package com.recoverpro.server.enums;

/**
 * Controlled action taxonomy for {@code audit_events}. Each action carries its own default
 * severity so severity isn't set ad hoc per call site.
 * <p>
 * This enum intentionally does NOT cover the business actions that already live on the five
 * existing per-domain audit tables (assignment_audit_logs, collection_audit_logs, ptp_audit_logs,
 * settlement_audit_logs, allocation_audit_logs) -- those keep their existing free-text action
 * columns and call sites unchanged. This taxonomy only covers categories that previously had no
 * controlled taxonomy at all: auth, RBAC, user lifecycle, platform admin, billing, bulk-import
 * lifecycle, and reports/export.
 * <p>
 * The DB CHECK constraint on {@code audit_events.action} (V085) must be kept in sync with this
 * enum by hand -- same convention already used for {@code report_jobs.report_type} (see V064).
 */
public enum AuditAction {

    // Auth
    AUTH_LOGIN_SUCCESS(AuditSeverity.INFO),
    AUTH_LOGIN_FAILED(AuditSeverity.WARNING),
    AUTH_LOGOUT(AuditSeverity.INFO),
    AUTH_PASSWORD_CHANGED(AuditSeverity.HIGH),
    AUTH_PASSWORD_RESET_REQUESTED(AuditSeverity.WARNING),
    AUTH_PASSWORD_RESET_COMPLETED(AuditSeverity.HIGH),
    AUTH_MFA_ENABLED(AuditSeverity.HIGH),
    AUTH_MFA_DISABLED(AuditSeverity.HIGH),
    AUTH_SESSION_REVOKED(AuditSeverity.WARNING),
    AUTH_TOKEN_THEFT_DETECTED(AuditSeverity.CRITICAL),
    // SYSTEM 09 TASK 9.4.b: distinct from ACCESS_DENIED -- this is "no valid credentials at all"
    // (missing/expired/blacklisted token, or a token for a since-deleted user), not "authenticated
    // but insufficient role." Keeping the two separate lets an audit query tell a credential-
    // guessing/token-replay attempt apart from a legitimate user hitting a permission wall.
    AUTH_UNAUTHORIZED(AuditSeverity.WARNING),

    // RBAC
    ROLE_GRANTED(AuditSeverity.HIGH),
    ROLE_REVOKED(AuditSeverity.HIGH),
    PERMISSION_GRANTED(AuditSeverity.HIGH),
    PERMISSION_REVOKED(AuditSeverity.HIGH),
    ACCESS_DENIED(AuditSeverity.WARNING),

    // User lifecycle
    USER_CREATED(AuditSeverity.INFO),
    USER_UPDATED(AuditSeverity.INFO),
    USER_DEACTIVATED(AuditSeverity.HIGH),
    USER_REACTIVATED(AuditSeverity.HIGH),
    USER_ROLE_CHANGED(AuditSeverity.HIGH),
    // SYSTEM 18 TASK 18.4: distinct from USER_DEACTIVATED -- an ordinary soft-delete is
    // reversible in spirit (the row survives, an admin could theoretically restore it) while this
    // is the GDPR-erasure path: PII is scrubbed everywhere it is duplicated, not just the users
    // row, and that is not undoable. Worth its own taxonomy entry so a compliance query can find
    // every erasure without guessing at metadata.
    USER_DATA_ERASED(AuditSeverity.CRITICAL),

    // Platform admin
    ORG_SUSPENDED(AuditSeverity.CRITICAL),
    ORG_REACTIVATED(AuditSeverity.HIGH),
    ORG_TRIAL_EXTENDED(AuditSeverity.INFO),
    // SYSTEM 18 TASK 18.2.c: soft-delete (retention window starts) vs. the scheduled job's later
    // hard purge -- two distinct, separately-auditable transitions, same reasoning as
    // USER_DATA_ERASED above.
    ORG_DELETED(AuditSeverity.CRITICAL),
    ORG_PURGED(AuditSeverity.CRITICAL),
    // SYSTEM 08 TASK 8.3.d: an org admin's own MFA-required toggle -- distinct from
    // FEATURE_FLAG_CHANGED (that taxonomy entry is specifically for FeatureFlag rows, a different
    // mechanism) and worth its own queryable action given its security relevance.
    ORG_MFA_POLICY_CHANGED(AuditSeverity.HIGH),
    ENTITLEMENT_GRANTED(AuditSeverity.HIGH),
    ENTITLEMENT_REVOKED(AuditSeverity.HIGH),
    CROSS_ORG_ACCESS(AuditSeverity.HIGH),
    FEATURE_FLAG_CHANGED(AuditSeverity.WARNING),

    // Billing
    SUBSCRIPTION_CREATED(AuditSeverity.INFO),
    SUBSCRIPTION_CHANGED(AuditSeverity.HIGH),
    SUBSCRIPTION_CANCELLED(AuditSeverity.HIGH),
    INVOICE_PAYMENT_FAILED(AuditSeverity.WARNING),
    REFUND_CREATED(AuditSeverity.HIGH),
    BILLING_OVERRIDE_APPLIED(AuditSeverity.CRITICAL),

    // Bulk-import lifecycle
    FILE_UPLOAD_INITIATED(AuditSeverity.INFO),
    FILE_PROCESSING_STARTED(AuditSeverity.INFO),
    FILE_PROCESSING_COMPLETED(AuditSeverity.INFO),
    FILE_PROCESSING_PARTIALLY_FAILED(AuditSeverity.WARNING),
    FILE_PROCESSING_FAILED(AuditSeverity.WARNING),
    FILE_UPLOAD_DELETED(AuditSeverity.WARNING),

    // Reports & export
    REPORT_GENERATED(AuditSeverity.INFO),
    REPORT_EXPORTED(AuditSeverity.INFO),
    DATA_EXPORTED(AuditSeverity.HIGH),
    AUDIT_LOG_EXPORTED(AuditSeverity.HIGH);

    private final AuditSeverity defaultSeverity;

    AuditAction(AuditSeverity defaultSeverity) {
        this.defaultSeverity = defaultSeverity;
    }

    public AuditSeverity defaultSeverity() {
        return defaultSeverity;
    }
}
