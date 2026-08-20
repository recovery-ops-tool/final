package com.recoverpro.server.enums;

/** Controlled resource taxonomy for {@code audit_events.resource_type}, so "everything that
 *  happened to resource X" queries don't depend on free-text agreement across call sites. */
public enum AuditResourceType {
    ALLOCATION,
    BORROWER,
    USER,
    ORGANIZATION,
    VISIT,
    COLLECTION,
    PTP,
    SETTLEMENT,
    FILE_UPLOAD,
    ROLE,
    SUBSCRIPTION,
    INVOICE,
    REPORT,
    FEATURE_FLAG,
    // SYSTEM 10 TASK 10.4.c: the resource an AUDIT_LOG_EXPORTED event is about is the audit log
    // itself (a filtered slice of unified_audit_events), not any of the domain resources above --
    // REPORT would misleadingly imply a ReportJob row exists for it.
    AUDIT_LOG,
    CALL_LOG
}
