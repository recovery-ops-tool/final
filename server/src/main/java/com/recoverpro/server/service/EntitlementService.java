package com.recoverpro.server.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Centralized "what is this specific org allowed to use, right now" check (Billing Ledger design
 * doc §6/§14). Controllers/services should call through here instead of checking plan names or
 * feature flags ad hoc -- {@code if (plan == "PRO")} scattered through the codebase is exactly
 * what this exists to prevent.
 */
public interface EntitlementService {

    boolean hasFeature(UUID organizationId, String flagKey);

    /** Empty means unlimited for this org (Enterprise, or no cap configured). */
    Optional<Long> getLimit(UUID organizationId, String limitKey);

    /** Live count vs. limit, not a maintained counter -- see EntitlementServiceImpl javadoc. */
    boolean canCreateUser(UUID organizationId);

    boolean canCreateAllocations(UUID organizationId, long additionalCount);

    /** TASK 20.4: this month's file-upload count vs. the org's plan cap. */
    boolean canUploadFile(UUID organizationId);

    /** TASK 20.4: current stored bytes (already-uploaded, non-deleted files) plus a pending
     *  upload's size vs. the org's plan storage cap. */
    boolean canUseStorage(UUID organizationId, long additionalBytes);

    /** TASK 20.4: this month's report-generation count vs. the org's plan cap. */
    boolean canGenerateReport(UUID organizationId);
}
