package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetMyAssignmentsTool implements LucienTool {

    private final AssignmentService assignmentService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "get_my_assignments"; }

    @Override
    public String description() {
        return "Get all active cases assigned to me today. Returns loan number, borrower name, outstanding amount and due date.";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    @Override public boolean isWriteOperation() { return false; }

    @Override public String humanReadableSummary(JsonNode args) { return "List today's active assignments"; }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        try {
            var cases = assignmentService.getMyActiveCases(
                    principal.getId(), principal.getOrganizationId(), PageRequest.of(0, 30));
            return objectMapper.writeValueAsString(cases.getContent());
        } catch (Exception e) {
            log.error("GetMyAssignmentsTool failed: {}", e.getMessage());
            return "{\"error\": \"Could not retrieve assignments: " + e.getMessage() + "\"}";
        }
    }
}
