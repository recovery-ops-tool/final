package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.dto.request.CreatePtpRequest;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.PtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreatePtpTool implements LucienTool {

    private final PtpService ptpService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "create_ptp"; }

    @Override
    public String description() {
        return "Create a Promise-to-Pay for a case. Requires allocationId, promisedDate (YYYY-MM-DD), promisedAmount, borrowerName, loanNumber, and optional contactNotes. WRITE — requires confirmation.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "allocationId":{"type":"string"},
                  "loanNumber":{"type":"string"},
                  "borrowerName":{"type":"string"},
                  "promisedDate":{"type":"string","description":"ISO date YYYY-MM-DD"},
                  "promisedAmount":{"type":"number"},
                  "contactNotes":{"type":"string"}
                },"required":["allocationId","loanNumber","borrowerName","promisedDate","promisedAmount"]}""";
    }

    @Override public boolean isWriteOperation() { return true; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return String.format("Create PTP for %s (loan %s): ₹%.2f promised by %s",
                args.path("borrowerName").asText("?"),
                args.path("loanNumber").asText("?"),
                args.path("promisedAmount").asDouble(0),
                args.path("promisedDate").asText("?"));
    }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        try {
            CreatePtpRequest req = CreatePtpRequest.builder()
                    .allocationId(UUID.fromString(args.get("allocationId").asText()))
                    .agentId(principal.getId())
                    .agentName(principal.getUsername())
                    .loanNumber(args.get("loanNumber").asText())
                    .borrowerName(args.get("borrowerName").asText())
                    .promisedDate(LocalDate.parse(args.get("promisedDate").asText()))
                    .promisedAmount(BigDecimal.valueOf(args.get("promisedAmount").asDouble()))
                    .contactNotes(args.path("contactNotes").asText(null))
                    .build();
            var result = ptpService.createPtp(req, principal.getId());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("CreatePtpTool failed: {}", e.getMessage());
            return "{\"error\": \"PTP creation failed: " + e.getMessage() + "\"}";
        }
    }
}
