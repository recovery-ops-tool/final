package com.recoverpro.server.observability;

import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SYSTEM 12 TASK 12.3.a: "Redis unreachable" is one of the alert categories the task lists by
 * name. Before this, a Redis outage was only ever visible reactively -- per-request, in
 * {@link com.recoverpro.server.security.jwt.JwtAuthenticationFilter}'s blacklist check (fails
 * closed, 503s the request, logs an ERROR line) or via {@code /actuator/health/readiness} flipping
 * DOWN for whichever external load balancer happens to be polling it -- neither actually pages
 * anyone. Same reasoning as {@link HikariPoolExhaustionMonitor} and {@link
 * CircuitBreakerAlertListener}: no live Prometheus/Alertmanager stack exists yet (see those two
 * classes' javadoc for why standing one up isn't this task's call to make unilaterally), so this
 * reuses the existing in-process {@link OpsAlertService} path instead, at negligible added cost --
 * one {@code PING} every 30s.
 * <p>
 * "Sustained," matching Hikari's monitor: a single dropped connection during a brief network blip
 * is not an incident. Only alerts once {@value #SUSTAINED_CHECKS} consecutive checks have failed,
 * and resets the streak the moment a check succeeds. {@link OpsAlertService}'s own cooldown still
 * applies on top, so an outage lasting hours doesn't re-page every 30s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUnreachableAlertMonitor {

    private static final long CHECK_INTERVAL_MS = 30_000;
    private static final int SUSTAINED_CHECKS = 3; // >= 60s of continuous unreachability before alerting

    private final StringRedisTemplate redisTemplate;
    private final OpsAlertService opsAlertService;

    private int consecutiveFailures = 0;

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    public void checkReachability() {
        try {
            try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
                connection.ping();
            }
            if (consecutiveFailures > 0) {
                log.info("RedisUnreachableAlertMonitor: Redis reachable again after {} failed check(s)",
                        consecutiveFailures);
            }
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            log.warn("RedisUnreachableAlertMonitor: PING failed (streak={}): {}",
                    consecutiveFailures, e.getMessage());
            if (consecutiveFailures >= SUSTAINED_CHECKS) {
                opsAlertService.alertJobFailure("RedisUnreachable",
                        "PING failed on " + consecutiveFailures + " consecutive checks (>= "
                                + ((SUSTAINED_CHECKS - 1) * CHECK_INTERVAL_MS / 1000) + "s)", e);
            }
        }
    }
}
