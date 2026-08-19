-- SYSTEM 09 TASK 9.4.b: 401 (no valid credentials at all) was previously indistinguishable from
-- 403 (ACCESS_DENIED) in the audit trail because it was never recorded there at all -- neither
-- RestAuthenticationEntryPoint nor JwtAuthenticationFilter's short-circuit rejection paths
-- (blacklisted token, invalid SSE ticket, token for a since-deleted user) wrote an audit_events
-- row. Adds AUTH_UNAUTHORIZED (application: AuditAction.AUTH_UNAUTHORIZED) as its own action, and
-- ANONYMOUS (application: AuditActorType.ANONYMOUS) as its own actor type -- SYSTEM already means
-- "this app itself acted," which is the wrong label for an unidentified external caller.

ALTER TABLE unified_audit_events
    DROP CONSTRAINT IF EXISTS chk_unified_audit_events_actor_type;
ALTER TABLE unified_audit_events
    ADD CONSTRAINT chk_unified_audit_events_actor_type CHECK (actor_type IN
        ('USER', 'SYSTEM', 'BACKGROUND_JOB', 'API_CLIENT', 'ANONYMOUS'));

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
        'USER_ROLE_CHANGED',
        -- Platform admin
        'ORG_SUSPENDED', 'ORG_REACTIVATED', 'ORG_TRIAL_EXTENDED',
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
