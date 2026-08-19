package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.CallingHoursGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class CheckCallingHoursTool implements LucienTool {

    private final CallingHoursGuard callingHoursGuard;
    private final OrgIsolationGuard orgIsolationGuard;

    @Override public String name() { return "check_calling_hours"; }

    @Override
    public String description() {
        return "Check if calling/contact is currently allowed under RBI compliance rules. Returns allowed status and denial reason if blocked.";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    @Override public boolean isWriteOperation() { return false; }

    @Override public String humanReadableSummary(JsonNode args) { return "Check if calling hours are active now"; }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        boolean allowed = callingHoursGuard.isAllowedFor(principal.getOrganizationId(), now);
        String denial = callingHoursGuard.denialReason(principal.getOrganizationId(), now);
        return String.format(
                "{\"allowed\": %b, \"currentTime\": \"%s\", \"reason\": %s}",
                allowed,
                now.format(DateTimeFormatter.ofPattern("HH:mm z")),
                denial == null ? "null" : "\"" + denial + "\""
        );
    }
}
