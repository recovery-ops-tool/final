package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.CreateNonContactableRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.NonContactableResponse;
import com.recoverpro.server.entity.NonContactable;
import com.recoverpro.server.repository.NonContactableRepository;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.NonContactableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NonContactableServiceImpl implements NonContactableService {

    private final NonContactableRepository repo;
    private final AllocationService allocationService;

    @Override
    @Transactional
    public NonContactableResponse create(CreateNonContactableRequest request, UUID agentId, UUID organizationId) {
        if (organizationId == null) {
            throw new BusinessException("Caller has no organization context");
        }

        AllocationResponse alloc = allocationService.getAllocationById(request.getAllocationId());
        if (alloc.getOrganizationId() == null || !alloc.getOrganizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Allocation not found");
        }

        NonContactable saved = repo.save(
                NonContactable.builder()
                        .organizationId(organizationId)
                        .allocationId(request.getAllocationId())
                        .visitId(request.getVisitId())
                        .agentId(agentId)
                        .reason(request.getReason())
                        .notes(request.getNotes())
                        .build()
        );

        log.info("Non-contactable recorded: case={} reason={} agent={}",
                saved.getAllocationId(), saved.getReason(), saved.getAgentId());

        return toResponse(saved);
    }

    private NonContactableResponse toResponse(NonContactable nc) {
        return NonContactableResponse.builder()
                .id(nc.getId())
                .organizationId(nc.getOrganizationId())
                .allocationId(nc.getAllocationId())
                .visitId(nc.getVisitId())
                .agentId(nc.getAgentId())
                .reason(nc.getReason())
                .notes(nc.getNotes())
                .createdAt(nc.getCreatedAt())
                .build();
    }
}
