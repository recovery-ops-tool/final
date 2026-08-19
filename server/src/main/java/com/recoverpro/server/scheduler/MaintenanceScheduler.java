package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.AgentShift;
import com.recoverpro.server.enums.ShiftStatus;
import com.recoverpro.server.repository.AgentShiftRepository;
import com.recoverpro.server.repository.AppNotificationRepository;
import com.recoverpro.server.repository.ChatMessageRepository;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.repository.PasswordResetTokenRepository;
import com.recoverpro.server.repository.RefreshTokenRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Periodic housekeeping tasks:
 * - Purge expired/revoked refresh tokens
 * - Purge expired password reset OTPs
 * - Auto-unlock accounts whose lockout period has elapsed
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceScheduler {

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentShiftRepository agentShiftRepository;
    private final AppNotificationRepository appNotificationRepository;
    private final OpsAlertService opsAlertService;

    @Value("${lucien.sessions.max-age-days:90}")
    private int sessionMaxAgeDays;

    @Value("${app.notifications.retention-days:90}")
    private int notificationRetentionDays;

    @Value("${agent.shift.max-hours:14}")
    private int shiftMaxHours;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredTokens() {
        try {
            Instant now = Instant.now();
            refreshTokenRepository.deleteExpiredTokens(now);
            int otpDeleted = passwordResetTokenRepository.deleteExpired(now);
            log.info("Token cleanup: OTPs removed={}", otpDeleted);
        } catch (Exception e) {
            log.error("MaintenanceScheduler: purgeExpiredTokens failed", e);
            opsAlertService.alertJobFailure("MaintenanceScheduler.purgeExpiredTokens",
                    "expired refresh token / password reset OTP cleanup", e);
        }
    }

    @Scheduled(fixedDelay = 900_000)
    @Transactional
    public void unlockExpiredAccounts() {
        try {
            userRepository.findExpiredLockouts(Instant.now()).forEach(user -> {
                userRepository.resetLockout(user.getId());
                log.info("Auto-unlocked account: id={}", user.getId());
            });
        } catch (Exception e) {
            // A repeated failure here means legitimately-lockout-expired users stay locked out
            // indefinitely -- a real user-facing outage, not just housekeeping.
            log.error("MaintenanceScheduler: unlockExpiredAccounts failed", e);
            opsAlertService.alertJobFailure("MaintenanceScheduler.unlockExpiredAccounts",
                    "auto-unlock of expired account lockouts", e);
        }
    }

    /**
     * markRead/dismiss are soft, and nothing else deleted notifications, so the
     * table only ever grew. Read/dismissed rows past the retention horizon are
     * removed; unread rows are kept regardless of age so an unseen alert is
     * never silently destroyed.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeSettledNotifications() {
        try {
            Instant cutoff = Instant.now().minusSeconds((long) notificationRetentionDays * 86400);
            int removed = appNotificationRepository.deleteSettledOlderThan(cutoff);
            log.info("Notification purge: {} read/dismissed rows older than {} days removed",
                    removed, notificationRetentionDays);
        } catch (Exception e) {
            log.error("MaintenanceScheduler: purgeSettledNotifications failed", e);
            opsAlertService.alertJobFailure("MaintenanceScheduler.purgeSettledNotifications",
                    "settled app-notification retention purge", e);
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeOldChatSessions() {
        try {
            Instant cutoff = Instant.now().minusSeconds((long) sessionMaxAgeDays * 86400);
            int messages = chatMessageRepository.deleteMessagesForSessionsOlderThan(cutoff);
            int sessions = chatSessionRepository.deleteSessionsOlderThan(cutoff);
            log.info("FRIDAY session purge: {} messages + {} sessions older than {} days removed",
                    messages, sessions, sessionMaxAgeDays);
        } catch (Exception e) {
            log.error("MaintenanceScheduler: purgeOldChatSessions failed", e);
            opsAlertService.alertJobFailure("MaintenanceScheduler.purgeOldChatSessions",
                    "Lucien chat session/message retention purge", e);
        }
    }

    @Scheduled(fixedDelay = 30 * 60_000)
    @Transactional
    public void autoEndDanglingShifts() {
        try {
            Instant cutoff = Instant.now().minusSeconds((long) shiftMaxHours * 3600);
            List<AgentShift> dangling = agentShiftRepository.findStaleActiveShifts(cutoff);
            if (dangling.isEmpty()) return;
            Instant now = Instant.now();
            for (AgentShift shift : dangling) {
                shift.setStatus(ShiftStatus.AUTO_ENDED);
                shift.setEndedAt(now);
            }
            agentShiftRepository.saveAll(dangling);
            log.warn("Auto-ended {} dangling shifts (no ping for {} h): {}",
                    dangling.size(), shiftMaxHours,
                    dangling.stream().map(s -> s.getId().toString()).toList());
        } catch (Exception e) {
            // Attendance/payroll-relevant -- a repeated failure means shifts that should have
            // auto-ended keep accruing hours indefinitely.
            log.error("MaintenanceScheduler: autoEndDanglingShifts failed", e);
            opsAlertService.alertJobFailure("MaintenanceScheduler.autoEndDanglingShifts",
                    "auto-ending dangling agent shifts", e);
        }
    }
}
