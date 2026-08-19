package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.ReassignRequest;
import com.recoverpro.server.enums.Priority;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReassignCaseTool implements LucienTool {

    private final AssignmentService assignmentService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "reassign_case"; }

    @Override
    public String description() {
        return "Reassign an existing assignment to a different field officer. MANAGER or TL role required. Requires assignmentId, newAgentId, assignmentDate, and reason. WRITE — requires confirmation.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "assignmentId":{"type":"string"},
                  "newAgentId":{"type":"string"},
                  "assignmentDate":{"type":"string","description":"ISO date YYYY-MM-DD"},
                  "reason":{"type":"string","minLength":10},
                  "priority":{"type":"string","enum":["LOW","NORMAL","HIGH","URGENT"]}
                },"required":["assignmentId","newAgentId","assignmentDate","reason"]}""";
    }

    @Override public boolean isWriteOperation() { return true; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return String.format("Reassign assignment %s to agent %s on %s (reason: %s)",
                args.path("assignmentId").asText("?"),
                args.path("newAgentId").asText("?"),
                args.path("assignmentDate").asText("?"),
                args.path("reason").asText("?"));
    }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        boolean isManager = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(PlatformConstants.ROLE_MANAGER)
                            || a.getAuthority().equals(PlatformConstants.ROLE_TL)
                            || a.getAuthority().equals(PlatformConstants.ROLE_ORG_ADMIN));
        if (!isManager) {
            throw new AccessDeniedException("reassign_case requires MANAGER or TL role.");
        }
        try {
            UUID assignmentId = UUID.fromString(args.get("assignmentId").asText());
            Priority priority = args.hasNonNull("priority")
                    ? Priority.valueOf(args.get("priority").asText())
                    : Priority.MEDIUM;
            ReassignRequest req = ReassignRequest.builder()
                    .newAgentId(UUID.fromString(args.get("newAgentId").asText()))
                    .assignmentDate(LocalDate.parse(args.get("assignmentDate").asText()))
                    .reason(args.get("reason").asText())
                    .priority(priority)
                    .build();
            var result = assignmentService.reassign(assignmentId, req, principal.getId());
            return objectMapper.writeValueAsString(result);
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("ReassignCaseTool failed: {}", e.getMessage());
            return "{\"error\": \"Reassignment failed: " + e.getMessage() + "\"}";
        }
    }
}
