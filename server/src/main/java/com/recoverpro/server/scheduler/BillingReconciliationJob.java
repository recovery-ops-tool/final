package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.PaymentProvider;
import com.recoverpro.server.service.PaymentProviderResolver;
import com.recoverpro.server.service.OpsAlertService;
import com.recoverpro.server.service.RazorpayWebhookService;
import com.recoverpro.server.service.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SYSTEM 19 TASK 19.4: periodically compares every org's LOCAL subscription status against what
 * the provider (Stripe or Razorpay) actually has on file, live -- not via the webhook mirror,
 * which is exactly the channel that could be the thing that's broken (a missed/failed webhook
 * delivery, {@code claimEvent}'s idempotency guard wrongly swallowing a real event, this app's own
 * bug). Catches the class of drift no webhook-driven sync can ever catch itself: the case where
 * the webhook never arrived at all.
 * <p>
 * Deliberately does NOT auto-correct a divergence it finds (TASK 19.4.b) -- a mismatch here is a
 * bug (in this app, in the webhook delivery path, or possibly at the provider), and silently
 * overwriting local state to match the provider would hide exactly the signal an operator needs
 * to find and fix the real cause. It only alerts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingReconciliationJob {

    private final OrgSubscriptionRepository subscriptionRepository;
    private final PaymentProviderResolver providerResolver;
    private final OpsAlertService opsAlertService;

    private record Divergence(java.util.UUID orgId, String providerSubscriptionId,
                               OrgSubscription.Status localState, String providerRawState) {}

    /** Runs at 03:30 daily, after the two other nightly maintenance jobs (PiiKeyRotationJob 01:30,
     *  OrganizationPurgeJob 02:30) -- this one makes live provider API calls, one per subscribed
     *  org, so it is deliberately not scheduled to overlap with anything else hitting the DB hard. */
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "billing_reconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        List<OrgSubscription> candidates = subscriptionRepository.findAll().stream()
                .filter(s -> s.getStripeSubscriptionId() != null || s.getRazorpaySubscriptionId() != null)
                .toList();

        List<Divergence> divergences = new ArrayList<>();
        int checked = 0;
        int fetchFailures = 0;
        for (OrgSubscription sub : candidates) {
            try {
                checkOne(sub).ifPresent(divergences::add);
                checked++;
            } catch (Exception e) {
                fetchFailures++;
                log.error("BillingReconciliationJob: status fetch failed for org {}: {}",
                        sub.getOrgId(), e.getMessage(), e);
            }
        }

        log.info("BillingReconciliationJob: checked {} org(s), {} divergence(s) found, {} fetch failure(s)",
                checked, divergences.size(), fetchFailures);

        if (!divergences.isEmpty()) {
            // SYSTEM 19 TASK 19.4.c: org id, local state, provider state, provider subscription id
            // for every divergence found this run -- one alert per RUN (not per org), same
            // convention PiiKeyRotationJob/DunningScheduler already use, so a bad run doesn't
            // storm the single ops alert channel.
            String detail = divergences.stream()
                    .map(d -> "org=" + d.orgId() + " providerSubscriptionId=" + d.providerSubscriptionId()
                            + " local=" + d.localState() + " provider=" + d.providerRawState())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            opsAlertService.alertJobFailure("BillingReconciliationJob.divergence",
                    divergences.size() + " org(s) diverged between local and provider subscription state: "
                            + detail, null);
        }
        if (fetchFailures > 0) {
            opsAlertService.alertJobFailure("BillingReconciliationJob.fetchFailures",
                    fetchFailures + " org(s) could not be checked this run (provider API errors) -- "
                            + "see application logs for which orgs and why", null);
        }
    }

    private Optional<Divergence> checkOne(OrgSubscription sub) {
        PaymentProvider provider = providerResolver.resolve(sub.getProvider());
        Optional<String> rawStatus = provider.fetchRemoteStatus(sub.getOrgId());
        if (rawStatus.isEmpty()) {
            return Optional.empty();
        }

        // Mapped through the SAME logic a real webhook sync would use, so this compares against
        // what this app itself would have set had the webhook actually arrived -- not a second,
        // independently-invented notion of equivalence.
        OrgSubscription.Status mapped = switch (sub.getProvider()) {
            case STRIPE -> StripeWebhookService.mapStatus(rawStatus.get());
            case RAZORPAY -> RazorpayWebhookService.mapStatus(rawStatus.get());
        };
        if (mapped == null) {
            // Pre-activation or otherwise-unmapped raw status (e.g. Razorpay "created") -- not
            // enough information to call this a divergence one way or the other.
            return Optional.empty();
        }

        if (mapped != sub.getStatus()) {
            String providerSubscriptionId = sub.getProvider() == com.recoverpro.server.enums.PaymentProviderType.STRIPE
                    ? sub.getStripeSubscriptionId() : sub.getRazorpaySubscriptionId();
            return Optional.of(new Divergence(sub.getOrgId(), providerSubscriptionId, sub.getStatus(), rawStatus.get()));
        }
        return Optional.empty();
    }
}
