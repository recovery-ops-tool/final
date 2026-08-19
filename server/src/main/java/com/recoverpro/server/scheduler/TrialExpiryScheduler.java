package com.recoverpro.server.scheduler;

import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.FeatureFlagService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TASK 28.2.b: closes the gap SYSTEM 28 TASK 28.1 flagged as its own known limitation --
 * {@code FeatureFlagService.effectivePlan()} correctly resolves an expired TRIAL to
 * {@code Plan.NONE}, but nothing re-provisions an org's flags just because time passed. Before
 * this job, a trial that expired with no other subscription event in between (webhook, admin
 * action, plan change) kept its last-provisioned STARTER-level access indefinitely -- the
 * literal fail-open gap TASK 28.1 fixed for brand-new orgs was still open for orgs whose trial
 * simply ran out the clock.
 *
 * <p>Mirrors {@link DunningScheduler}'s exact shape (same daily-sweep-plus-per-org-REQUIRES_NEW
 * pattern, same reminder-then-terminal-transition structure) since it is solving the identical
 * problem shape: a subscription-status deadline nothing proactively enforces. Expiry transitions
 * the subscription to {@code CANCELLED} (this codebase has no dedicated {@code EXPIRED} status --
 * {@code effectivePlan()} already treats CANCELLED and an expired TRIAL identically, so this
 * keeps the model to the states that already exist) and re-provisions flags -- 28.2.c's "never
 * delete data" is satisfied structurally, since neither this job nor {@code provisionFlagsFor}
 * touches any row outside {@code feature_flags}/{@code org_subscriptions}. A customer who converts
 * after expiry (a real webhook/admin action) re-provisions correctly via the existing
 * subscription-change wiring (SYSTEM 20 TASK 20.3) with no special-casing needed here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrialExpiryScheduler {

    private static final Set<Integer> REMINDER_DAYS_BEFORE_EXPIRY = Set.of(3, 1);

    private final OrgSubscriptionRepository subRepo;
    private final NotificationService notificationService;
    private final FeatureFlagService featureFlagService;
    private final AuditService auditService;
    private final OpsAlertService opsAlertService;

    @Scheduled(cron = "${app.scheduler.trial-expiry-cron:0 30 9 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "TrialExpiryScheduler.run", lockAtLeastFor = "PT30S", lockAtMostFor = "PT15M")
    public void run() {
        log.info("TrialExpiryScheduler: starting sweep");
        try {
            List<UUID> trialIds = subRepo.findByStatus(OrgSubscription.Status.TRIAL)
                    .stream().map(OrgSubscription::getId).toList();
            int reminders = 0;
            int expired = 0;
            for (UUID subId : trialIds) {
                try {
                    Outcome outcome = processOne(subId);
                    if (outcome == Outcome.REMINDED) reminders++;
                    if (outcome == Outcome.EXPIRED) expired++;
                } catch (Exception e) {
                    log.error("TrialExpiryScheduler: failed processing subscription {}", subId, e);
                    opsAlertService.alertJobFailure("TrialExpiryScheduler.run",
                            "subscriptionId=" + subId, e);
                }
            }
            log.info("TrialExpiryScheduler: swept {} TRIAL orgs, {} reminders, {} expired",
                    trialIds.size(), reminders, expired);
        } catch (Exception e) {
            log.error("TrialExpiryScheduler: sweep failed", e);
            opsAlertService.alertJobFailure("TrialExpiryScheduler.run", "sweep listing", e);
        }
    }

    enum Outcome { NONE, REMINDED, EXPIRED }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome processOne(UUID subId) {
        OrgSubscription sub = subRepo.findById(subId).orElse(null);
        // Re-check status/trialEndsAt fresh: the org may have converted, been comped, or been
        // extended since the sweep list was built moments earlier.
        if (sub == null || sub.getStatus() != OrgSubscription.Status.TRIAL || sub.getTrialEndsAt() == null) {
            return Outcome.NONE;
        }

        Instant now = Instant.now();
        if (!sub.getTrialEndsAt().isAfter(now)) {
            expireTrial(sub);
            return Outcome.EXPIRED;
        }

        long daysUntilExpiry = ChronoUnit.DAYS.between(now, sub.getTrialEndsAt());
        if (REMINDER_DAYS_BEFORE_EXPIRY.contains((int) daysUntilExpiry)) {
            sendReminder(sub, daysUntilExpiry);
            return Outcome.REMINDED;
        }
        return Outcome.NONE;
    }

    private void sendReminder(OrgSubscription sub, long daysUntilExpiry) {
        notificationService.createForOrgRole(sub.getOrgId(), PlatformConstants.ROLE_ORG_ADMIN,
                NotificationType.ORG_TRIAL_EXPIRING_SOON,
                "Your trial ends in " + daysUntilExpiry + " day" + (daysUntilExpiry == 1 ? "" : "s"),
                "Upgrade to a paid plan to keep access to your data and features without "
                        + "interruption when the trial ends.");
        log.info("TrialExpiryScheduler: sent {}-day-remaining reminder for org {}", daysUntilExpiry, sub.getOrgId());
    }

    private void expireTrial(OrgSubscription sub) {
        Instant expiredAt = sub.getTrialEndsAt();
        sub.setStatus(OrgSubscription.Status.CANCELLED);
        subRepo.save(sub);
        featureFlagService.provisionFlagsFor(sub);

        notificationService.createForOrgRole(sub.getOrgId(), PlatformConstants.ROLE_ORG_ADMIN,
                NotificationType.ORG_TRIAL_EXPIRED,
                "Your trial has ended",
                "Your trial period has ended and paid features are no longer available. Your data "
                        + "is intact -- subscribe to restore full access.");

        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.SUBSCRIPTION_CANCELLED)
                .resourceType(AuditResourceType.SUBSCRIPTION)
                .resourceId(sub.getOrgId().toString())
                .reason("Trial period expired")
                .actorTypeOverride(AuditActorType.BACKGROUND_JOB)
                .organizationIdOverride(sub.getOrgId())
                .metadata(Map.of("trigger", "trial_expired", "trialEndsAt", String.valueOf(expiredAt)))
                .build());

        log.warn("TrialExpiryScheduler: expired trial for org {} (trialEndsAt was {})", sub.getOrgId(), expiredAt);
    }
}
