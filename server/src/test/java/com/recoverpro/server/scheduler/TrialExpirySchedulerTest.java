package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.FeatureFlagService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.OpsAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 28.2.b: before this job existed, an expired TRIAL kept its last-provisioned access
 * indefinitely -- {@code effectivePlan()} (SYSTEM 28 TASK 28.1) correctly resolves an expired
 * trial to {@code Plan.NONE}, but nothing ever called {@code provisionFlagsFor} again just
 * because time passed. Mirrors {@code DunningSchedulerTest}'s pattern exactly, same problem shape.
 */
@ExtendWith(MockitoExtension.class)
class TrialExpirySchedulerTest {

    @Mock private OrgSubscriptionRepository subRepo;
    @Mock private NotificationService notificationService;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private AuditService auditService;
    @Mock private OpsAlertService opsAlertService;

    private TrialExpiryScheduler scheduler;
    private UUID orgId;
    private UUID subId;

    @BeforeEach
    void setUp() {
        scheduler = new TrialExpiryScheduler(subRepo, notificationService, featureFlagService, auditService, opsAlertService);
        orgId = UUID.randomUUID();
        subId = UUID.randomUUID();
    }

    /**
     * A small positive buffer, not exactly {@code days} out: {@code processOne} re-reads
     * {@code Instant.now()} itself, strictly after this helper's own {@code Instant.now()} call --
     * with zero buffer, that real (if tiny) elapsed time floor-truncates {@code ChronoUnit.DAYS}
     * down to {@code days - 1} intermittently, exactly like {@code DunningScheduler}'s own
     * days-since arithmetic would in the opposite (elapsing-forward) direction. This mirrors how
     * production naturally avoids the boundary too: a real {@code trialEndsAt} is never set to the
     * exact instant the sweep later runs.
     */
    private OrgSubscription trialEndingIn(long days) {
        return OrgSubscription.builder()
                .id(subId)
                .orgId(orgId)
                .status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.STARTER)
                .trialEndsAt(Instant.now().plus(days, ChronoUnit.DAYS).plusSeconds(30))
                .build();
    }

    @Test
    void processOne_threeDaysRemaining_sendsReminderAndDoesNotExpire() {
        when(subRepo.findById(subId)).thenReturn(Optional.of(trialEndingIn(3)));

        scheduler.processOne(subId);

        verify(notificationService).createForOrgRole(eq(orgId), anyString(),
                eq(NotificationType.ORG_TRIAL_EXPIRING_SOON), anyString(), anyString());
        verify(subRepo, never()).save(any());
        verify(featureFlagService, never()).provisionFlagsFor(any());
    }

    @Test
    void processOne_oneDayRemaining_sendsReminder() {
        when(subRepo.findById(subId)).thenReturn(Optional.of(trialEndingIn(1)));

        scheduler.processOne(subId);

        verify(notificationService).createForOrgRole(eq(orgId), anyString(),
                eq(NotificationType.ORG_TRIAL_EXPIRING_SOON), anyString(), anyString());
    }

    @Test
    void processOne_twoDaysRemaining_notAReminderDay_noOp() {
        when(subRepo.findById(subId)).thenReturn(Optional.of(trialEndingIn(2)));

        scheduler.processOne(subId);

        verify(notificationService, never()).createForOrgRole(any(), anyString(), any(), anyString(), anyString());
        verify(subRepo, never()).save(any());
    }

    @Test
    void processOne_trialExpired_cancelsAndRevokesEntitlementsAndAudits() {
        OrgSubscription sub = trialEndingIn(-1); // trialEndsAt in the past
        when(subRepo.findById(subId)).thenReturn(Optional.of(sub));

        scheduler.processOne(subId);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
        verify(subRepo).save(sub);
        verify(featureFlagService).provisionFlagsFor(sub);
        verify(notificationService).createForOrgRole(eq(orgId), anyString(),
                eq(NotificationType.ORG_TRIAL_EXPIRED), anyString(), anyString());
        verify(auditService).record(any());
    }

    @Test
    void processOne_trialEndsAtExactlyNow_treatedAsExpired() {
        OrgSubscription sub = OrgSubscription.builder()
                .id(subId).orgId(orgId).status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.STARTER).trialEndsAt(Instant.now().minusMillis(1)).build();
        when(subRepo.findById(subId)).thenReturn(Optional.of(sub));

        scheduler.processOne(subId);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
    }

    @Test
    void processOne_alreadyConvertedSinceSweepListBuilt_isNoOp() {
        OrgSubscription converted = OrgSubscription.builder()
                .id(subId).orgId(orgId).status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.GROWTH).trialEndsAt(Instant.now().minus(5, ChronoUnit.DAYS)).build();
        when(subRepo.findById(subId)).thenReturn(Optional.of(converted));

        scheduler.processOne(subId);

        verify(notificationService, never()).createForOrgRole(any(), anyString(), any(), anyString(), anyString());
        verify(subRepo, never()).save(any());
    }
}
