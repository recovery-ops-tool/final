package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SendNotificationTool implements LucienTool {

    private final NotificationService notificationService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "send_notification"; }

    @Override
    public String description() {
        return "Send an in-app notification to another user within the same org. Requires recipientId, title, body, and notificationType. WRITE — requires confirmation.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "recipientId":{"type":"string","description":"UUID of the recipient user"},
                  "title":{"type":"string","maxLength":200},
                  "body":{"type":"string","maxLength":1000},
                  "notificationType":{"type":"string","enum":["APPROVAL_DECIDED","REPORT_READY","USER_REQUEST_DECIDED"]}
                },"required":["recipientId","title","body","notificationType"]}""";
    }

    @Override public boolean isWriteOperation() { return true; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return String.format("Send '%s' notification to user %s: %s",
                args.path("notificationType").asText(),
                args.path("recipientId").asText("?"),
                args.path("title").asText("?"));
    }

    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.assertBelongsToOrg(principal.getOrganizationId());
        try {
            UUID recipientId = UUID.fromString(args.get("recipientId").asText());
            NotificationType type = NotificationType.valueOf(args.get("notificationType").asText());
            var notification = notificationService.create(
                    recipientId,
                    principal.getOrganizationId(),
                    type,
                    args.get("title").asText(),
                    args.get("body").asText());
            return objectMapper.writeValueAsString(notification);
        } catch (Exception e) {
            log.error("SendNotificationTool failed: {}", e.getMessage());
            return "{\"error\": \"Notification failed: " + e.getMessage() + "\"}";
        }
    }
}
