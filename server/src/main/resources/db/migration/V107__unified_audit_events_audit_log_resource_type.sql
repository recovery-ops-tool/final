-- SYSTEM 10 TASK 10.4.c: the new auditor-export endpoint audits itself via
-- AuditAction.AUDIT_LOG_EXPORTED (already existed in the action taxonomy, unused, since V085) --
-- but no resource_type value represents "the audit log itself." Reusing REPORT would misleadingly
-- imply a ReportJob row exists for the export. Adds AUDIT_LOG (application: AuditResourceType.AUDIT_LOG).

ALTER TABLE unified_audit_events
    DROP CONSTRAINT IF EXISTS chk_unified_audit_events_resource_type;
ALTER TABLE unified_audit_events
    ADD CONSTRAINT chk_unified_audit_events_resource_type CHECK (resource_type IN
        ('ALLOCATION', 'BORROWER', 'USER', 'ORGANIZATION', 'VISIT', 'COLLECTION',
         'PTP', 'SETTLEMENT', 'FILE_UPLOAD', 'ROLE', 'SUBSCRIPTION', 'INVOICE',
         'REPORT', 'FEATURE_FLAG', 'AUDIT_LOG'));
