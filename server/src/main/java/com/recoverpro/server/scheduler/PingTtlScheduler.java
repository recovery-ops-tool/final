package com.recoverpro.server.scheduler;

import com.recoverpro.server.repository.AgentLocationPingRepository;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 30-day TTL on {@code agent_location_pings} (design-doc §7.3 privacy/storage).
 *
 * Default: daily at 03:00 IST, deletes pings older than 30 days.
 * Retention configurable via {@code app.field-ops.ping-retention-days}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PingTtlScheduler {

    private final AgentLocationPingRepository pingRepository;
    private final OpsAlertService opsAlertService;

    @Value("${app.field-ops.ping-retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${app.field-ops.ping-purge-cron:0 0 3 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "PingTtlScheduler.purge", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional
    public void purge() {
        try {
            Instant cutoff = Instant.now().minusSeconds((long) retentionDays * 86400);
            int deleted = pingRepository.deleteOlderThan(cutoff);
            log.info("PingTtlScheduler: purged {} pings older than {} (retention={}d)",
                    deleted, cutoff, retentionDays);
        } catch (Exception e) {
            // Retention-policy purge, not just housekeeping -- a silent repeated failure here
            // means location pings outlive the org's stated 30-day retention window, a
            // compliance gap, not just a storage-growth one.
            log.error("PingTtlScheduler: purge failed", e);
            opsAlertService.alertJobFailure("PingTtlScheduler.purge",
                    "agent location ping retention purge", e);
        }
    }
}
