package com.recoverpro.server.dto.response;

import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.AuditSeverity;
import com.recoverpro.server.enums.AuditSource;
import lombok.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventResponse {

    private UUID id;
    private UUID organizationId;
    private UUID actorUserId;
    private String actorName;
    private AuditActorType actorType;
    private String actorRole;
    private AuditAction action;
    private AuditResourceType resourceType;
    private String resourceId;
    private AuditSeverity severity;
    private AuditResult result;
    private AuditSource source;
    private String requestId;
    private String correlationId;
    private String ipAddress;
    private String userAgent;
    private String reason;
    private Map<String, Object> beforeState;
    private Map<String, Object> afterState;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
