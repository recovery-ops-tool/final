package com.recoverpro.server.service;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.config.StripeConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.entity.Payment;
import com.recoverpro.server.entity.PlatformInvoice;
import com.recoverpro.server.entity.ProcessedStripeEvent;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.enums.PaymentProviderType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.PaymentRepository;
import com.recoverpro.server.repository.PlatformInvoiceRepository;
import com.recoverpro.server.repository.ProcessedStripeEventRepository;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/**
 * Syncs {@link OrgSubscription} state from Stripe subscription-billing webhook events.
 * Unrelated to loan-repayment collections -- Stripe is used only for this platform's
 * own SaaS billing to organizations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final ProcessedStripeEventRepository processedEventRepository;
    private final OrgSubscriptionRepository subscriptionRepository;
    private final PlatformInvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final FeatureFlagService featureFlagService;
    private final StripeConfig stripeConfig;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /**
     * Atomically claims an event id so it is processed exactly once under Stripe's
     * at-least-once retry semantics. Flushes immediately so the duplicate-key
     * violation surfaces inside this method, not at the caller's transaction commit.
     *
     * @return true if this call claimed the event (caller should process it),
     *         false if it was already processed (caller should skip).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimEvent(String eventId, String eventType) {
        try {
            processedEventRepository.saveAndFlush(ProcessedStripeEvent.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .processedAt(Instant.now())
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Stripe event {} already processed (type={}), skipping", eventId, eventType);
            return false;
        }
    }

    /**
     * Undoes claimEvent() after a failed dispatch, so a subsequent Stripe retry can
     * re-claim and re-attempt this event instead of forever seeing it as already
     * processed. Runs in its own transaction, same as claimEvent(), since the claim
     * itself already committed independently and there is nothing for an enclosing
     * transaction to roll back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseEventClaim(String eventId) {
        processedEventRepository.deleteById(eventId);
    }

    @Transactional
    public void handleCheckoutCompleted(Session session) {
        OrgSubscription sub = requireByCustomerId(session.getCustomer());
        sub.setStripeSubscriptionId(session.getSubscription());
        sub.setStatus(OrgSubscription.Status.ACTIVE);
        subscriptionRepository.save(sub);
        log.info("Stripe checkout completed: org={}, subscription={}", sub.getOrgId(), session.getSubscription());
        auditSubscriptionEvent(AuditAction.SUBSCRIPTION_CREATED, sub.getOrgId(),
                Map.of("status", sub.getStatus().name()));
    }

    /** {@code customer.subscription.created} and {@code .updated} carry the same
     * authoritative snapshot, so both are synced identically. No ordering check -- callers that
     * know their own event's timestamp should prefer the overload below; this one exists for
     * callers (backfills, tests) with no event envelope to compare against. */
    @Transactional
    public void handleSubscriptionUpserted(Subscription subscription) {
        handleSubscriptionUpserted(subscription, null);
    }

    /**
     * SYSTEM 19 TASK 19.3.c: {@code eventCreatedAt} is the webhook Event's own {@code created}
     * timestamp (Stripe's delivery order is not guaranteed -- retries and network jitter can
     * reorder deliveries). If it is OLDER than the last event already applied to this org, this
     * is a stale/out-of-order delivery: skip the write entirely rather than regressing state that
     * a newer delivery already advanced past. {@code null} (the single-arg overload) always
     * applies, matching the pre-TASK-19.3 behavior for callers with no event timestamp to offer.
     */
    @Transactional
    public void handleSubscriptionUpserted(Subscription subscription, Instant eventCreatedAt) {
        OrgSubscription sub = requireByCustomerId(subscription.getCustomer());
        if (isStale(sub, eventCreatedAt)) {
            log.warn("Stripe subscription.updated for org {} is older than the last applied event "
                    + "({} < {}) -- skipping to avoid regressing state", sub.getOrgId(),
                    eventCreatedAt, sub.getLastWebhookEventAt());
            return;
        }
        sub.setStripeSubscriptionId(subscription.getId());
        sub.setStatus(mapStatus(subscription.getStatus()));
        sub.setPlan(resolvePlan(subscription));
        sub.setPlanAmount(resolvePlanAmount(subscription));
        sub.setCurrentPeriodEnd(toInstant(subscription.getCurrentPeriodEnd()));
        sub.setCancelAtPeriodEnd(Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
        if (eventCreatedAt != null) {
            sub.setLastWebhookEventAt(eventCreatedAt);
        }
        subscriptionRepository.save(sub);
        featureFlagService.provisionFlagsFor(sub);
        log.info("Stripe subscription synced: org={}, status={}, plan={}",
                sub.getOrgId(), sub.getStatus(), sub.getPlan());
        auditSubscriptionEvent(AuditAction.SUBSCRIPTION_CHANGED, sub.getOrgId(),
                Map.of("status", sub.getStatus().name(), "plan", sub.getPlan().name()));
    }

    @Transactional
    public void handleSubscriptionDeleted(Subscription subscription) {
        handleSubscriptionDeleted(subscription, null);
    }

    /** See {@link #handleSubscriptionUpserted(Subscription, Instant)}'s javadoc for the ordering
     *  reasoning -- same guard, applied here too since this also overwrites subscription state. */
    @Transactional
    public void handleSubscriptionDeleted(Subscription subscription, Instant eventCreatedAt) {
        OrgSubscription sub = requireByCustomerId(subscription.getCustomer());
        if (isStale(sub, eventCreatedAt)) {
            log.warn("Stripe subscription.deleted for org {} is older than the last applied event, skipping",
                    sub.getOrgId());
            return;
        }
        sub.setStatus(OrgSubscription.Status.CANCELLED);
        if (eventCreatedAt != null) {
            sub.setLastWebhookEventAt(eventCreatedAt);
        }
        subscriptionRepository.save(sub);
        featureFlagService.provisionFlagsFor(sub);
        log.info("Stripe subscription cancelled: org={}", sub.getOrgId());
        auditSubscriptionEvent(AuditAction.SUBSCRIPTION_CANCELLED, sub.getOrgId(), Map.of());
    }

    private static boolean isStale(OrgSubscription sub, Instant eventCreatedAt) {
        return eventCreatedAt != null && sub.getLastWebhookEventAt() != null
                && eventCreatedAt.isBefore(sub.getLastWebhookEventAt());
    }

    @Transactional
    public void handleInvoicePaid(Invoice invoice) {
        upsertInvoice(invoice);
        subscriptionRepository.findByStripeCustomerId(invoice.getCustomer()).ifPresent(sub -> {
            // SYSTEM 19 TASK 19.2.c: recover from CANCELLED as well as PAST_DUE. DunningScheduler's
            // grace-period expiry (expireSubscription()) only ever flips the LOCAL status -- it
            // never calls Stripe's own cancel API -- so a subscription this app has marked
            // CANCELLED can still be actively billed by Stripe. If Stripe fires invoice.paid at
            // all, Stripe itself has NOT terminated the subscription (a truly Stripe-cancelled
            // subscription never generates another invoice), so this is unambiguous proof of a
            // real, current payment and access must be restored immediately, not at the next
            // billing cycle.
            boolean recoverable = sub.getStatus() == OrgSubscription.Status.PAST_DUE
                    || sub.getStatus() == OrgSubscription.Status.CANCELLED;
            if (recoverable) {
                OrgSubscription.Status previous = sub.getStatus();
                sub.setStatus(OrgSubscription.Status.ACTIVE);
                sub.setPastDueSince(null);
                subscriptionRepository.save(sub);
                featureFlagService.provisionFlagsFor(sub);
                log.info("Stripe subscription recovered from {}: org={}", previous, sub.getOrgId());
                notificationService.createForOrgRole(sub.getOrgId(), PlatformConstants.ROLE_ORG_ADMIN,
                        NotificationType.ORG_PAYMENT_RECOVERED,
                        "Payment received", "Your subscription is active again -- thanks for settling up.");
                auditSubscriptionEvent(AuditAction.SUBSCRIPTION_CHANGED, sub.getOrgId(),
                        Map.of("status", "ACTIVE", "previousStatus", previous.name(), "reason", "payment recovered"));
            }
        });
    }

    @Transactional
    public void handleInvoicePaymentFailed(Invoice invoice) {
        upsertInvoice(invoice);
        subscriptionRepository.findByStripeCustomerId(invoice.getCustomer()).ifPresent(sub -> {
            // Only stamp pastDueSince (and notify) on the transition into PAST_DUE -- Stripe's own
            // retry schedule can fire this webhook again while already PAST_DUE, which must not
            // reset the dunning clock or re-spam the same notification (Billing Ledger §12).
            boolean enteringPastDue = sub.getStatus() != OrgSubscription.Status.PAST_DUE;
            if (enteringPastDue) {
                sub.setPastDueSince(Instant.now());
            }
            sub.setStatus(OrgSubscription.Status.PAST_DUE);
            subscriptionRepository.save(sub);
            featureFlagService.provisionFlagsFor(sub);
            log.warn("Stripe invoice payment failed, org marked PAST_DUE: org={}", sub.getOrgId());
            if (enteringPastDue) {
                notificationService.createForOrgRole(sub.getOrgId(), PlatformConstants.ROLE_ORG_ADMIN,
                        NotificationType.ORG_PAYMENT_FAILED,
                        "Payment failed",
                        "We couldn't process your latest payment. We'll retry automatically -- "
                                + "please update your payment method to avoid any service interruption.");
            }
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.INVOICE_PAYMENT_FAILED)
                    .resourceType(AuditResourceType.INVOICE)
                    .resourceId(invoice.getId())
                    .result(AuditResult.FAILURE)
                    .actorTypeOverride(AuditActorType.SYSTEM)
                    .organizationIdOverride(sub.getOrgId())
                    .metadata(Map.of("stripeInvoiceId", String.valueOf(invoice.getId())))
                    .build());
        });
    }

    /**
     * Mirrors a Stripe invoice into {@code platform_invoices} so the billing
     * console can aggregate collected revenue in SQL rather than fanning out to
     * the Stripe API once per customer on every page load.
     *
     * <p>Upsert keyed on {@code stripe_invoice_id}: one invoice arrives
     * repeatedly across its lifecycle (finalized -> paid, or finalized ->
     * payment_failed -> paid on retry) and every event carries the full current
     * snapshot, so the newest write wins.
     *
     * <p>An invoice for an unknown customer is logged and dropped rather than
     * raising. Stripe accounts routinely hold customers created outside this
     * application, and throwing here would make Stripe retry the event forever.
     */
    @Transactional
    public void upsertInvoice(Invoice invoice) {
        OrgSubscription sub = subscriptionRepository.findByStripeCustomerId(invoice.getCustomer()).orElse(null);
        if (sub == null) {
            log.warn("Stripe invoice {} references unknown customer {}, not mirrored",
                    invoice.getId(), invoice.getCustomer());
            return;
        }

        PlatformInvoice row = invoiceRepository.findByProviderInvoiceId(invoice.getId())
                .orElseGet(() -> PlatformInvoice.builder()
                        .provider(PaymentProviderType.STRIPE)
                        .providerInvoiceId(invoice.getId())
                        .build());

        row.setOrgId(sub.getOrgId());
        row.setProviderCustomerId(invoice.getCustomer());
        row.setNumber(invoice.getNumber());
        row.setStatus(invoice.getStatus());
        row.setAmountDue(invoice.getAmountDue() == null ? 0L : invoice.getAmountDue());
        row.setAmountPaid(invoice.getAmountPaid() == null ? 0L : invoice.getAmountPaid());
        row.setCurrency(invoice.getCurrency() == null ? "inr" : invoice.getCurrency());
        row.setPeriodStart(toInstant(invoice.getPeriodStart()));
        row.setPeriodEnd(toInstant(invoice.getPeriodEnd()));
        row.setIssuedAt(toInstant(invoice.getCreated()));
        row.setHostedInvoiceUrl(invoice.getHostedInvoiceUrl());
        row.setInvoicePdfUrl(invoice.getInvoicePdf());
        row.setProviderPaymentRef(invoice.getPaymentIntent());

        // paid_at drives every revenue aggregation, so it is set only on a real
        // settlement and cleared again if Stripe later voids the invoice.
        if ("paid".equals(invoice.getStatus())) {
            if (row.getPaidAt() == null) {
                row.setPaidAt(toInstant(invoice.getStatusTransitions() != null
                        ? invoice.getStatusTransitions().getPaidAt()
                        : null));
            }
            if (row.getPaidAt() == null) {
                row.setPaidAt(Instant.now());
            }
        } else {
            row.setPaidAt(null);
        }

        invoiceRepository.save(row);
        log.info("Stripe invoice mirrored: org={}, invoice={}, status={}, paid={} paise",
                sub.getOrgId(), invoice.getId(), invoice.getStatus(), row.getAmountPaid());

        mirrorPayment(sub.getOrgId(), row, invoice);
    }

    /**
     * Mirrors a {@link Payment} row keyed on the invoice's PaymentIntent id, alongside the
     * {@link PlatformInvoice} mirror above (Billing Ledger design doc §24). Skipped entirely
     * when Stripe hasn't attempted payment yet ({@code invoice.getPaymentIntent()} is null,
     * e.g. a just-finalized invoice awaiting its first charge attempt).
     * <p>
     * Deliberately mirrors the raw invoice-level status ("paid"/"open"/"void"/"uncollectible")
     * rather than a normalized success/fail flag: {@code Invoice} stays "open" through Stripe's
     * entire dunning retry cycle (see {@link #handleInvoicePaymentFailed}), so a specific failed
     * ATTEMPT cannot be distinguished from "not yet attempted" without expanding the
     * PaymentIntent itself, which this webhook path does not fetch. {@code paymentMethodType} is
     * left null for the same reason -- it lives on the PaymentIntent/Charge, not the Invoice.
     * Only {@code capturedAt} (on "paid") and {@code failedAt} (on "uncollectible", i.e. Stripe's
     * own retries exhausted) are populated from data already on hand.
     */
    private void mirrorPayment(UUID orgId, PlatformInvoice invoiceRow, Invoice invoice) {
        String paymentIntentId = invoice.getPaymentIntent();
        if (paymentIntentId == null) {
            return;
        }
        Payment payment = paymentRepository
                .findByProviderAndProviderPaymentId(PaymentProviderType.STRIPE, paymentIntentId)
                .orElseGet(() -> Payment.builder()
                        .provider(PaymentProviderType.STRIPE)
                        .providerPaymentId(paymentIntentId)
                        .build());
        payment.setOrganizationId(orgId);
        payment.setInvoiceId(invoiceRow.getId());
        payment.setAmountMinorUnits(invoiceRow.getAmountPaid() != null && invoiceRow.getAmountPaid() > 0
                ? invoiceRow.getAmountPaid() : invoiceRow.getAmountDue());
        payment.setCurrency(invoiceRow.getCurrency());
        payment.setStatus(invoice.getStatus());
        payment.setCapturedAt("paid".equals(invoice.getStatus()) ? invoiceRow.getPaidAt() : null);
        if ("uncollectible".equals(invoice.getStatus()) && payment.getFailedAt() == null) {
            payment.setFailedAt(Instant.now());
            payment.setFailureReason("Stripe dunning retries exhausted");
        }
        paymentRepository.save(payment);
    }

    /** Webhook-driven -- no HTTP session, no SecurityContext -- so actor is always SYSTEM and
     *  organizationId must be supplied explicitly rather than read from RlsOrgIdHolder. */
    private void auditSubscriptionEvent(AuditAction action, UUID orgId, Map<String, Object> metadata) {
        auditService.record(AuditEventRequest.builder()
                .action(action)
                .resourceType(AuditResourceType.SUBSCRIPTION)
                .resourceId(orgId != null ? orgId.toString() : null)
                .actorTypeOverride(AuditActorType.SYSTEM)
                .organizationIdOverride(orgId)
                .metadata(metadata)
                .build());
    }

    private OrgSubscription requireByCustomerId(String stripeCustomerId) {
        return subscriptionRepository.findByStripeCustomerId(stripeCustomerId)
                .orElseThrow(() -> new BusinessException(
                        "No OrgSubscription found for Stripe customer " + stripeCustomerId));
    }

    private OrgSubscription.Plan resolvePlan(Subscription subscription) {
        if (subscription.getItems() == null || subscription.getItems().getData().isEmpty()) {
            return OrgSubscription.Plan.NONE;
        }
        String priceId = subscription.getItems().getData().get(0).getPrice().getId();
        if (priceId.equals(stripeConfig.getPriceGrowth())) return OrgSubscription.Plan.GROWTH;
        if (priceId.equals(stripeConfig.getPriceEnterprise())) return OrgSubscription.Plan.ENTERPRISE;
        if (priceId.equals(stripeConfig.getPriceStarter())) return OrgSubscription.Plan.STARTER;
        log.warn("Unrecognized Stripe price id {}, defaulting to STARTER", priceId);
        return OrgSubscription.Plan.STARTER;
    }

    /** Stripe's {@code unit_amount} is in the smallest currency unit (paise for INR). */
    private static BigDecimal resolvePlanAmount(Subscription subscription) {
        if (subscription.getItems() == null || subscription.getItems().getData().isEmpty()) {
            return null;
        }
        Long unitAmount = subscription.getItems().getData().get(0).getPrice().getUnitAmount();
        return unitAmount == null ? null : BigDecimal.valueOf(unitAmount, 2);
    }

    /** Exposed for {@code BillingReconciliationJob} (SYSTEM 19 TASK 19.4), which needs to map a
     *  freshly-fetched Stripe status the SAME way a real webhook sync would, so a divergence
     *  alert compares apples to apples rather than reimplementing this mapping a second time. */
    public static OrgSubscription.Status mapStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "trialing" -> OrgSubscription.Status.TRIAL;
            case "active" -> OrgSubscription.Status.ACTIVE;
            case "past_due", "unpaid", "incomplete" -> OrgSubscription.Status.PAST_DUE;
            case "canceled", "incomplete_expired", "paused" -> OrgSubscription.Status.CANCELLED;
            default -> OrgSubscription.Status.INACTIVE;
        };
    }

    private static Instant toInstant(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }
}
