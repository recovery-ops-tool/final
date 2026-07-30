package com.recoverpro.server.lucien.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.lucien.agent.PendingAction;
import com.recoverpro.server.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;

    public String execute(String toolName, JsonNode args, UserPrincipal principal) {
        LucienTool tool = registry.resolve(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));
        log.info("Executing tool={} for user={}", toolName, principal.getId());
        return tool.execute(args, principal);
    }

    /** Called by ConfirmationService after user confirms a WRITE action. */
    public String executeConfirmed(PendingAction action, UserPrincipal principal) {
        if (!principal.getId().equals(action.getPrincipalId())) {
            throw new AccessDeniedException("Principal mismatch: this action belongs to a different user.");
        }
        try {
            JsonNode args = objectMapper.readTree(action.getToolArgsJson());
            return execute(action.getToolName(), args, principal);
        } catch (Exception e) {
            log.error("Failed to execute confirmed tool={}: {}", action.getToolName(), e.getMessage());
            throw new RuntimeException("Failed to execute confirmed action: " + e.getMessage(), e);
        }
    }

    /**
     * Same principal-ownership check as executeConfirmed, but executes with caller-supplied
     * args instead of re-parsing action.getToolArgsJson() — used by VisitInterviewService to
     * merge in server-verified allocationId/GPS/staged-image keys that were never part of the
     * model's original tool call. Exceptions (e.g. BusinessException validation failures) are
     * propagated as-is, not wrapped, so the caller can handle them.
     */
    public String executeWithOverride(PendingAction action, JsonNode overrideArgs, UserPrincipal principal) {
        if (!principal.getId().equals(action.getPrincipalId())) {
            throw new AccessDeniedException("Principal mismatch: this action belongs to a different user.");
        }
        return execute(action.getToolName(), overrideArgs, principal);
    }
}
