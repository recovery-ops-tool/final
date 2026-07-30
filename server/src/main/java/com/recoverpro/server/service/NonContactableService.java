package com.recoverpro.server.service;

import com.recoverpro.server.dto.request.CreateNonContactableRequest;
import com.recoverpro.server.dto.response.NonContactableResponse;

import java.util.UUID;

public interface NonContactableService {

    /** @throws com.recoverpro.server.common.exception.BusinessException if the caller has no
     * organization context.
     * @throws com.recoverpro.server.common.exception.ResourceNotFoundException if the allocation
     * doesn't belong to the caller's org. */
    NonContactableResponse create(CreateNonContactableRequest request, UUID agentId, UUID organizationId);
}
