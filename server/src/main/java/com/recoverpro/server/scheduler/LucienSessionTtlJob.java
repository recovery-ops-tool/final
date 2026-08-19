package com.recoverpro.server.scheduler;

import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Closes Lucien chat sessions that have been idle for more than 1 hour.
 * Runs every 5 minutes; ShedLock prevents duplicate execution on multi-pod deployments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LucienSessionTtlJob {

    private final ChatSessionRepository sessionRepository;
    private final OpsAlertService opsAlertService;

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "lucien_session_ttl", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    @Transactional
    public void closeIdleSessions() {
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minusSeconds(3600);
            int closed = sessionRepository.closeIdleSessions(cutoff, now);
            if (closed > 0) {
                log.info("LucienSessionTtl: closed {} idle session(s) (idle > 1h)", closed);
            }
        } catch (Exception e) {
            log.error("LucienSessionTtl: closeIdleSessions failed", e);
            opsAlertService.alertJobFailure("LucienSessionTtlJob.closeIdleSessions",
                    "closing idle Lucien chat sessions", e);
        }
    }
}
