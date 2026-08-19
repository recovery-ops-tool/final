package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.VisitLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetVisitHistoryTool implements LucienTool {

    private final VisitLogService visitLogService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "get_visit_history"; }

    @Override
    public String description() {
        return "Get today's visit log for my cases. Returns visit status, contactability, disposition, and notes.";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    @Override public boolean isWriteOperation() { return false; }

    @Override public String humanReadableSummary(JsonNode args) { return "Show today's visit history"; }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        try {
            var visits = visitLogService.getTodayVisits(principal.getId());
            return objectMapper.writeValueAsString(visits);
        } catch (Exception e) {
            log.error("GetVisitHistoryTool failed: {}", e.getMessage());
            return "{\"error\": \"Could not retrieve visit history: " + e.getMessage() + "\"}";
        }
    }
}
