package com.recoverpro.server.lucien.agent;

import com.recoverpro.server.lucien.tool.ToolExecutor;
import com.recoverpro.server.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves a pending WRITE tool confirmation (design-doc §6.3).
 * Redis down → gate fails-closed (no writes).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmationService {

    private static final String REDIS_KEY_PREFIX = "lucien:pending:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ToolExecutor toolExecutor;

    /**
     * Confirm or cancel a pending write action.
     *
     * @return A human-readable result message.
     * @throws IllegalStateException if no pending action found (TTL expired or wrong session).
     */
    public String resolve(String sessionId, boolean confirmed, UserPrincipal principal) {
        String key = REDIS_KEY_PREFIX + sessionId;
        Object raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            throw new IllegalStateException(
                    "No pending action for session " + sessionId + " — it may have timed out. Please retry.");
        }

        PendingAction action = (PendingAction) raw;

        if (!confirmed) {
            redisTemplate.delete(key);
            log.info("Action cancelled by user={} for session={}, tool={}",
                    principal.getId(), sessionId, action.getToolName());
            return "Action cancelled: " + action.getHumanSummary();
        }

        log.info("Executing confirmed action tool={} for user={} session={}",
                action.getToolName(), principal.getId(), sessionId);

        String result = toolExecutor.executeConfirmed(action, principal);
        redisTemplate.delete(key);

        log.info("Confirmed action complete: tool={} session={}", action.getToolName(), sessionId);
        return result;
    }

    /** Returns the pending action summary without consuming it. */
    public PendingAction peek(String sessionId) {
        Object raw = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + sessionId);
        if (raw == null) return null;
        return (PendingAction) raw;
    }

    /** Consumes (deletes) the pending action without executing it — used by
     * VisitInterviewService after a successful or cancelled confirm-visit call, whose
     * execution path bypasses resolve() (it needs to merge in server-verified args first). */
    public void discard(String sessionId) {
        redisTemplate.delete(REDIS_KEY_PREFIX + sessionId);
    }
}
