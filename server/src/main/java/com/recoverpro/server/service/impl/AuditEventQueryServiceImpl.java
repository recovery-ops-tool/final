package com.recoverpro.server.service.impl;

import com.recoverpro.server.dto.request.AuditEventFilterRequest;
import com.recoverpro.server.dto.response.AuditEventResponse;
import com.recoverpro.server.entity.AuditEvent;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.repository.AuditEventRepository;
import com.recoverpro.server.repository.AuditEventSpecification;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditEventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditEventQueryServiceImpl implements AuditEventQueryService {

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> search(AuditEventFilterRequest filter, UUID orgIdOverride, Pageable pageable) {
        Page<AuditEvent> page = auditEventRepository.findAll(
                AuditEventSpecification.withFilters(filter, orgIdOverride), pageable);
        Map<UUID, User> usersById = batchUsersById(page.getContent());
        return page.map(e -> toResponse(e, usersById));
    }

    private Map<UUID, User> batchUsersById(List<AuditEvent> entries) {
        List<UUID> ids = entries.stream()
                .map(AuditEvent::getActorUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
    }

    private AuditEventResponse toResponse(AuditEvent e, Map<UUID, User> usersById) {
        User actor = e.getActorUserId() != null ? usersById.get(e.getActorUserId()) : null;
        String actorName = actor != null ? actor.getFirstName() + " " + actor.getLastName() : null;
        return AuditEventResponse.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .actorUserId(e.getActorUserId())
                .actorName(actorName)
                .actorType(e.getActorType())
                .actorRole(e.getActorRole())
                .action(e.getAction())
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .severity(e.getSeverity())
                .result(e.getResult())
                .source(e.getSource())
                .requestId(e.getRequestId())
                .correlationId(e.getCorrelationId())
                .ipAddress(e.getIpAddress())
                .userAgent(e.getUserAgent())
                .reason(e.getReason())
                .beforeState(e.getBeforeState())
                .afterState(e.getAfterState())
                .metadata(e.getMetadata())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
