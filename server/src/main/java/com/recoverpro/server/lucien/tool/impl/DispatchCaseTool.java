package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.BulkAssignRequest;
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
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchCaseTool implements LucienTool {

    private final AssignmentService assignmentService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "dispatch_case"; }

    @Override
    public String description() {
        return "Assign an unassigned case to a field officer. MANAGER role required. Requires allocationId, agentId, assignmentDate. WRITE — requires confirmation.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "allocationId":{"type":"string"},
                  "agentId":{"type":"string","description":"UUID of the field officer to assign"},
                  "assignmentDate":{"type":"string","description":"ISO date YYYY-MM-DD"},
                  "priority":{"type":"string","enum":["LOW","NORMAL","HIGH","URGENT"],"default":"NORMAL"}
                },"required":["allocationId","agentId","assignmentDate"]}""";
    }

    @Override public boolean isWriteOperation() { return true; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return String.format("Dispatch case %s to agent %s on %s",
                args.path("allocationId").asText("?"),
                args.path("agentId").asText("?"),
                args.path("assignmentDate").asText("?"));
    }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        boolean isManager = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(PlatformConstants.ROLE_MANAGER)
                            || a.getAuthority().equals(PlatformConstants.ROLE_TL)
                            || a.getAuthority().equals(PlatformConstants.ROLE_ORG_ADMIN));
        if (!isManager) {
            throw new AccessDeniedException("dispatch_case requires MANAGER or TL role.");
        }
        try {
            Priority priority = args.hasNonNull("priority")
                    ? Priority.valueOf(args.get("priority").asText()) : Priority.MEDIUM;
            BulkAssignRequest req = BulkAssignRequest.builder()
                    .allocationIds(List.of(UUID.fromString(args.get("allocationId").asText())))
                    .agentId(UUID.fromString(args.get("agentId").asText()))
                    .organizationId(principal.getOrganizationId())
                    .assignmentDate(LocalDate.parse(args.get("assignmentDate").asText()))
                    .priority(priority)
                    .build();
            var result = assignmentService.bulkAssign(req, principal.getId());
            return objectMapper.writeValueAsString(result);
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("DispatchCaseTool failed: {}", e.getMessage());
            return "{\"error\": \"Dispatch failed: " + e.getMessage() + "\"}";
        }
    }
}
