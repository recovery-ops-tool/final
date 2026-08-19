package com.recoverpro.server.dto.request;

import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.AuditSeverity;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventFilterRequest {

    private Instant from;
    private Instant to;
    private UUID actorUserId;
    private AuditAction action;
    private AuditResourceType resourceType;
    private AuditSeverity severity;
    private AuditResult result;
}
