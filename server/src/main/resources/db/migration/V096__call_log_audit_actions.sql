-- =============================================================================
-- V096__call_log_audit_actions.sql
-- =============================================================================
-- Adds the call-log lifecycle to unified_audit_events' controlled taxonomy
-- (CALL_LOG resource_type; CALL_STARTED/CALL_RECORDING_UPLOADED/CALL_COMPLETED/
-- CALL_RECORDING_ACCESSED actions), so CallLogServiceImpl's audit writes are
-- accepted by the CHECK constraints defined in V085. Per V085's own convention,
-- adding a new action/resource_type requires updating AuditAction.java /
-- AuditResourceType.java and this constraint by hand -- done together in the
-- same change as CallLogServiceImpl's new auditService.record() calls.
--
-- CALL_RECORDING_ACCESSED is the compliance-critical one: it's the only
-- previously-unaudited event where someone actually listens to a borrower's
-- recorded voice (playback is already role-gated to ORG_ADMIN/MANAGER/TL/
-- PLATFORM_ADMIN at the controller), hence HIGH severity vs INFO for the rest.
-- =============================================================================

ALTER TABLE unified_audit_events
    DROP CONSTRAINT chk_unified_audit_events_resource_type;

ALTER TABLE unified_audit_events
    ADD CONSTRAINT chk_unified_audit_events_resource_type CHECK (resource_type IN
        ('ALLOCATION', 'BORROWER', 'USER', 'ORGANIZATION', 'VISIT', 'COLLECTION',
         'PTP', 'SETTLEMENT', 'FILE_UPLOAD', 'ROLE', 'SUBSCRIPTION', 'INVOICE',
         'REPORT', 'FEATURE_FLAG', 'CALL_LOG'));

ALTER TABLE unified_audit_events
    DROP CONSTRAINT chk_unified_audit_events_action;

ALTER TABLE unified_audit_events
    ADD CONSTRAINT chk_unified_audit_events_action CHECK (action IN (
        -- Auth
        'AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAILED', 'AUTH_LOGOUT',
        'AUTH_PASSWORD_CHANGED', 'AUTH_PASSWORD_RESET_REQUESTED',
        'AUTH_PASSWORD_RESET_COMPLETED', 'AUTH_MFA_ENABLED', 'AUTH_MFA_DISABLED',
        'AUTH_SESSION_REVOKED', 'AUTH_TOKEN_THEFT_DETECTED',
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
        'REPORT_GENERATED', 'REPORT_EXPORTED', 'DATA_EXPORTED', 'AUDIT_LOG_EXPORTED',
        -- Call recording
        'CALL_STARTED', 'CALL_RECORDING_UPLOADED', 'CALL_COMPLETED',
        'CALL_RECORDING_ACCESSED'
    ));
