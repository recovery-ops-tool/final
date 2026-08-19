package com.recoverpro.server.observability;

import com.recoverpro.server.service.OpsAlertService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SYSTEM 02 TASK 2.3: HikariCP's own {@code hikaricp_connections_pending} gauge (exposed on
 * {@code /actuator/prometheus} since Spring Boot's Micrometer auto-binding unwraps
 * {@link com.recoverpro.server.config.RlsAwareDataSource} via {@code DelegatingDataSource} to find
 * the real {@code HikariDataSource} underneath -- verified directly, not assumed) tells an external
 * Prometheus/Alertmanager setup that pool exhaustion is happening, but no such setup exists yet
 * (SYSTEM 12 hasn't run). Until it does, this polls the same gauge in-process and reuses the
 * existing {@link OpsAlertService} email/notification path so pool exhaustion pages someone instead
 * of only ever showing up after the fact in a metrics dashboard nobody was watching.
 * <p>
 * "Sustained," per the task's own wording, not "any blip": a single request queueing for a few
 * milliseconds during a normal traffic spike is not an incident. Only alerts once pending > 0 has
 * been observed on {@value #SUSTAINED_CHECKS} consecutive checks ({@value #CHECK_INTERVAL_MS}ms
 * apart, so >= {@code (SUSTAINED_CHECKS-1) * CHECK_INTERVAL_MS} of continuous exhaustion), and
 * resets the streak the moment pending returns to zero. {@link OpsAlertService}'s own cooldown
 * still applies on top of this, so a pool stuck exhausted for hours doesn't re-page every 30s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HikariPoolExhaustionMonitor {

    private static final String GAUGE_NAME = "hikaricp.connections.pending";
    private static final long CHECK_INTERVAL_MS = 30_000;
    private static final int SUSTAINED_CHECKS = 4; // >= 90s of continuous exhaustion before alerting

    private final MeterRegistry meterRegistry;
    private final OpsAlertService opsAlertService;

    private int consecutivePositiveChecks = 0;
    private boolean loggedMissingGaugeOnce = false;

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    public void checkPendingConnections() {
        Gauge gauge = meterRegistry.find(GAUGE_NAME).gauge();
        if (gauge == null) {
            // Expected in a test/dev context where the datasource isn't a real Hikari pool, or if
            // metrics export is disabled -- not worth alerting on, just note it once.
            if (!loggedMissingGaugeOnce) {
                log.info("HikariPoolExhaustionMonitor: '{}' gauge not registered, skipping checks", GAUGE_NAME);
                loggedMissingGaugeOnce = true;
            }
            return;
        }

        double pending = gauge.value();
        if (pending > 0) {
            consecutivePositiveChecks++;
            log.debug("HikariPoolExhaustionMonitor: pending={} (streak={})", pending, consecutivePositiveChecks);
            if (consecutivePositiveChecks >= SUSTAINED_CHECKS) {
                opsAlertService.alertJobFailure(
                        "HikariConnectionPoolExhaustion",
                        "pending=" + pending + " sustained for >= "
                                + ((SUSTAINED_CHECKS - 1) * CHECK_INTERVAL_MS / 1000) + "s",
                        null);
            }
        } else {
            consecutivePositiveChecks = 0;
        }
    }
}
