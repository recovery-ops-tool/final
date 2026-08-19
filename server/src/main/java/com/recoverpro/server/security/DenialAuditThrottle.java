package com.recoverpro.server.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SYSTEM 09 TASK 9.4.c: a denial storm from one actor against one endpoint (a scanner retrying a
 * missing/blacklisted token, or a misbehaving client looping on a 403) must not turn into one
 * {@code unified_audit_events} row per request -- that table is meant for "did someone attempt
 * X," not a full request log, and every {@link com.recoverpro.server.service.AuditService#record}
 * call is its own {@code REQUIRES_NEW} transaction, so an unthrottled write-per-request on the
 * highest-volume, least-authenticated traffic path (401s have no rate limiting ahead of them,
 * unlike login) is a self-inflicted load/DoS risk, not just table bloat.
 *
 * <p>Coalesces by (actor, endpoint) into a fixed window: only the first denial for a given pair
 * within the window is recorded, using Redis {@code SETNX}-with-TTL so the throttle state doesn't
 * need its own table or cleanup job. Deliberately coarse -- the endpoint key is the raw request
 * path (path variables and all), so {@code GET /api/v1/borrowers/{id}} against two different
 * borrower ids counts as two separate keys, not one. That's an accepted trade-off: perfect
 * dedup would need route-pattern matching this class doesn't have, and the coarse version already
 * catches the actual attack shape (one endpoint hammered repeatedly), which is what matters for
 * a security trail, not a perf-analytics rollup.
 *
 * <p>Fails OPEN on a Redis error (records anyway) rather than closed: unlike the JWT blacklist
 * check (which fails closed because letting a revoked token through is a live compromise),
 * silently dropping the audit trail during a Redis outage is strictly worse than a few extra
 * writes -- that's exactly the moment a real attack is most likely to also be happening unwatched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DenialAuditThrottle {

    private static final String KEY_PREFIX = "audit:denial-throttle:";
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    /**
     * @return true if this (actorKey, resourceKey) pair hasn't been recorded in the current
     *         window -- i.e. the caller should go ahead and write the audit row.
     */
    public boolean shouldRecord(String actorKey, String resourceKey) {
        try {
            String key = KEY_PREFIX + actorKey + ":" + resourceKey;
            Boolean firstInWindow = redisTemplate.opsForValue().setIfAbsent(key, "1", WINDOW);
            return Boolean.TRUE.equals(firstInWindow);
        } catch (Exception e) {
            log.warn("Denial-audit throttle check failed, recording anyway: {}", e.getMessage());
            return true;
        }
    }
}
