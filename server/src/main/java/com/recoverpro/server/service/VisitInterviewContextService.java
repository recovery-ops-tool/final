package com.recoverpro.server.service;

import java.util.UUID;

/**
 * Builds the single-case context block injected into Lucien's system prompt during a
 * visit-interview session (allocation_id bound), as opposed to AgentContextService/
 * ContextAssembler, which are FO-wide (today's whole caseload) and used for the general
 * assistant.
 */
public interface VisitInterviewContextService {

    /** @throws com.recoverpro.server.common.exception.ResourceNotFoundException if the
     * allocation doesn't exist or doesn't belong to the caller's org (enforced by
     * AllocationService.getAllocationById via OrgIsolationGuard). */
    String buildContextBlock(UUID allocationId);
}
