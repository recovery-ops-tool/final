-- SYSTEM 18 TASK 18.2.c: PlatformOrganizationController.delete() used to hard-delete the
-- Organization row outright (guarded only by "zero users left"). A misclick against an org that
-- happens to have no users yet had no recovery path. deleted_at starts a retention window;
-- OrganizationPurgeJob hard-purges once it elapses. deletion_reason is operator-facing context
-- for whoever reviews the org during that window.
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS deletion_reason VARCHAR(500);

-- Set once OrganizationPurgeJob tombstones the row after the retention window elapses. NOT a
-- literal row delete: unified_audit_events.organization_id is a hard FK to this table and
-- trg_unified_audit_events_immutable (V085, reusing V006's function) blocks UPDATE as well as
-- DELETE on every audit row, including the implicit UPDATE Postgres would run for an
-- ON DELETE SET NULL action -- so an org with any audit history literally cannot be row-deleted
-- without weakening that immutability guarantee. Purging instead scrubs name/code/contact fields
-- and removes the org's own subscription/feature-flag rows, keeping the id (and therefore every
-- audit row's FK) intact.
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS purged_at TIMESTAMP WITHOUT TIME ZONE;

-- SYSTEM 18 TASK 18.2/18.4: new AuditAction taxonomy entries -- same convention V098 used to
-- extend this CHECK constraint (kept in sync with enums/AuditAction.java by hand).
ALTER TABLE unified_audit_events
    DROP CONSTRAINT IF EXISTS chk_unified_audit_events_action;
ALTER TABLE unified_audit_events
    ADD CONSTRAINT chk_unified_audit_events_action CHECK (action IN (
        -- Auth
        'AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAILED', 'AUTH_LOGOUT',
        'AUTH_PASSWORD_CHANGED', 'AUTH_PASSWORD_RESET_REQUESTED',
        'AUTH_PASSWORD_RESET_COMPLETED', 'AUTH_MFA_ENABLED', 'AUTH_MFA_DISABLED',
        'AUTH_SESSION_REVOKED', 'AUTH_TOKEN_THEFT_DETECTED', 'AUTH_UNAUTHORIZED',
        -- RBAC
        'ROLE_GRANTED', 'ROLE_REVOKED', 'PERMISSION_GRANTED', 'PERMISSION_REVOKED',
        'ACCESS_DENIED',
        -- User lifecycle
        'USER_CREATED', 'USER_UPDATED', 'USER_DEACTIVATED', 'USER_REACTIVATED',
        'USER_ROLE_CHANGED', 'USER_DATA_ERASED',
        -- Platform admin
        'ORG_SUSPENDED', 'ORG_REACTIVATED', 'ORG_TRIAL_EXTENDED', 'ORG_DELETED', 'ORG_PURGED',
        'ENTITLEMENT_GRANTED', 'ENTITLEMENT_REVOKED', 'CROSS_ORG_ACCESS',
        'FEATURE_FLAG_CHANGED',
        -- Billing
        'SUBSCRIPTION_CREATED', 'SUBSCRIPTION_CHANGED', 'SUBSCRIPTION_CANCELLED',
        'INVOICE_PAYMENT_FAILED', 'REFUND_CREATED', 'BILLING_OVERRIDE_APPLIED',
        -- Bulk-import lifecycle
        'FILE_UPLOAD_INITIATED', 'FILE_PROCESSING_STARTED',
        'FILE_PROCESSING_COMPLETED', 'FILE_PROCESSING_PARTIALLY_FAILED',
        'FILE_PROCESSING_FAILED', 'FILE_UPLOAD_DELETED',
        -- Reports & export
        'REPORT_GENERATED', 'REPORT_EXPORTED', 'DATA_EXPORTED', 'AUDIT_LOG_EXPORTED'
    ));
