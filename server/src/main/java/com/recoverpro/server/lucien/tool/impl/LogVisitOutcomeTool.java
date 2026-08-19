package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.dto.request.VisitLogRequest;
import com.recoverpro.server.enums.Contactability;
import com.recoverpro.server.enums.Disp;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.VisitLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogVisitOutcomeTool implements LucienTool {

    private final VisitLogService visitLogService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "log_visit_outcome"; }

    @Override
    public String description() {
        return "Log the outcome of a field visit for a case. Requires allocationId, visitDate, contactability, disposition (disp), latitude, longitude, and optional notes. WRITE — requires confirmation.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "allocationId":{"type":"string"},
                  "organizationId":{"type":"string"},
                  "visitDate":{"type":"string","description":"ISO date YYYY-MM-DD"},
                  "contactability":{"type":"string","enum":["CONTACTED","NOT_CONTACTED","REFUSED"]},
                  "disp":{"type":"string","description":"Disposition code e.g. PTP, NC, RTP"},
                  "latitude":{"type":"number"},
                  "longitude":{"type":"number"},
                  "visitNotes":{"type":"string"}
                },"required":["allocationId","visitDate","contactability","disp","latitude","longitude"]}""";
    }

    @Override public boolean isWriteOperation() { return true; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return String.format("Log visit for case %s on %s — disposition: %s",
                args.path("allocationId").asText("?"),
                args.path("visitDate").asText(LocalDate.now().toString()),
                args.path("disp").asText("?"));
    }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        try {
            UUID orgId = args.hasNonNull("organizationId")
                    ? UUID.fromString(args.get("organizationId").asText())
                    : principal.getOrganizationId();

            VisitLogRequest req = VisitLogRequest.builder()
                    .allocationId(UUID.fromString(args.get("allocationId").asText()))
                    .organizationId(orgId)
                    .visitDate(LocalDate.parse(args.path("visitDate").asText(LocalDate.now().toString())))
                    .contactability(Contactability.valueOf(args.get("contactability").asText()))
                    .disp(Disp.valueOf(args.get("disp").asText()))
                    .latitude(args.get("latitude").asDouble())
                    .longitude(args.get("longitude").asDouble())
                    .visitNotes(args.path("visitNotes").asText(null))
                    .build();

            var result = visitLogService.create(req, null, null, null,
                    principal.getId(), principal.getId());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("LogVisitOutcomeTool failed: {}", e.getMessage());
            return "{\"error\": \"Visit log failed: " + e.getMessage() + "\"}";
        }
    }
}
