package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.PtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetCasePtpStatusTool implements LucienTool {

    private final PtpService ptpService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "get_case_ptp_status"; }

    @Override
    public String description() {
        return "Get all Promise-to-Pay records for a specific case (allocation). Requires allocationId.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "allocationId":{"type":"string","description":"UUID of the allocation/case"}
                },"required":["allocationId"]}""";
    }

    @Override public boolean isWriteOperation() { return false; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return "Look up PTP status for case " + args.path("allocationId").asText();
    }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        try {
            UUID allocationId = UUID.fromString(args.get("allocationId").asText());
            var ptps = ptpService.getPtpsByAllocationId(allocationId);
            return objectMapper.writeValueAsString(ptps);
        } catch (Exception e) {
            log.error("GetCasePtpStatusTool failed: {}", e.getMessage());
            return "{\"error\": \"Could not retrieve PTP status: " + e.getMessage() + "\"}";
        }
    }
}
