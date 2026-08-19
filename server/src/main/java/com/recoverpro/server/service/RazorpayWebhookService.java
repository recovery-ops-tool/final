package com.recoverpro.server.service;

import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.config.RazorpayConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.entity.Payment;
import com.recoverpro.server.entity.PlatformInvoice;
import com.recoverpro.server.entity.ProcessedRazorpayEvent;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.enums.PaymentProviderType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.PaymentRepository;
import com.recoverpro.server.repository.PlatformInvoiceRepository;
import com.recoverpro.server.repository.ProcessedRazorpayEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Syncs {@link OrgSubscription} state from Razorpay subscription-billing webhook events. Mirrors
 * {@code StripeWebhookService}'s structure (claim/dispatch/audit/notify). See
 * {@code docs/BILLING-PROVIDER-PARITY.md} (SYSTEM 19 TASK 19.1) for the full handler-by-handler
 * comparison against Stripe -- as of that document, plan/period/invoice mirroring is at parity;
 * a Razorpay subscription's {@code cancelAtPeriodEnd} is set synchronously at the point of the
 * API call ({@link com.recoverpro.server.service.impl.RazorpayPaymentProvider#cancelSubscription}
 * ), not parsed from a webhook field, since Razorpay's subscription entity does not reliably
 * expose one.
 * <p>
 * Razorpay Subscriptions has no separate "Invoice" object the way Stripe does -- a successful
 * {@code subscription.charged} payment IS the billing event, so {@link #mirrorInvoice} synthesizes
 * a {@code PlatformInvoice} row directly from the subscription + payment webhook entities rather
 * than listening for a dedicated invoice.* event (there isn't one for this product).
 * <p>
 * Event-name-to-status mapping below (subscription.charged/pending/halted/cancelled) follows
 * Razorpay's public Subscriptions webhook documentation as of this codebase's authoring date --
 * verify against a live webhook payload capture before this handler processes real events, same
 * caveat as {@link com.recoverpro.server.service.impl.RazorpayPaymentProvider}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayWebhookService {

    private final ProcessedRazorpayEventRepository processedEventRepository;
    private final OrgSubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final PlatformInvoiceRepository invoiceRepository;
    private final FeatureFlagService featureFlagService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final RazorpayConfig razorpayConfig;

    /** Same reasoning as {@code StripeWebhookService.claimEvent}: flush-immediately insert so the
     *  duplicate-key violation surfaces inline, not at the caller's transaction commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimEvent(String eventId, String eventType) {
        try {
            processedEventRepository.saveAndFlush(ProcessedRazorpayEvent.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .processedAt(Instant.now())
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Razorpay event {} already processed (type={}), skipping", eventId, eventType);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseEventClaim(String eventId) {
        processedEventRepository.deleteById(eventId);
    }

    /**
     * Dispatches by the webhook envelope's {@code event} field. {@code payload} is the raw
     * {@code payload.subscription.entity} / {@code payload.payment.entity} JSON object per
     * Razorpay's envelope shape ({@code {"event": ..., "payload": {"subscription": {"entity":
     * {...}}}}}) -- extraction of the right sub-object happens in the controller, not here, so
     * this method only ever sees one entity's fields.
     */
    @Transactional
    public void handleSubscriptionEvent(String eventType, JSONObject subscriptionEntity) {
        handleSubscriptionEvent(eventType, subscriptionEntity, null, null);
    }

    @Transactional
    public void handleSubscriptionEvent(String eventType, JSONObject subscriptionEntity, JSONObject paymentEntity) {
        handleSubscriptionEvent(eventType, subscriptionEntity, paymentEntity, null);
    }

    /**
     * SYSTEM 19 TASK 19.3.c: {@code eventCreatedAt} is the webhook envelope's own
     * {@code created_at} (already extracted by {@code RazorpayWebhookController} for its
     * idempotency key) -- same ordering guard and reasoning as
     * {@code StripeWebhookService#handleSubscriptionUpserted(Subscription, Instant)}. {@code null}
     * (the 2- and 3-arg overloads) always applies, matching pre-TASK-19.3 behavior.
     */
    @Transactional
    public void handleSubscriptionEvent(String eventType, JSONObject subscriptionEntity,
                                        JSONObject paymentEntity, Instant eventCreatedAt) {
        String razorpaySubscriptionId = subscriptionEntity.optString("id", null);
        if (razorpaySubscriptionId == null) {
            log.warn("Razorpay {} event carried no subscription id, skipping", eventType);
            return;
        }
        Optional<OrgSubscription> maybeSub =
                subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSub.isEmpty()) {
            log.warn("Razorpay subscription {} not linked to any org, event {} dropped",
                    razorpaySubscriptionId, eventType);
            return;
        }
        OrgSubscription sub = maybeSub.get();

        if (eventCreatedAt != null && sub.getLastWebhookEventAt() != null
                && eventCreatedAt.isBefore(sub.getLastWebhookEventAt())) {
            log.warn("Razorpay {} for org {} is older than the last applied event ({} < {}) -- "
                    + "skipping to avoid regressing state", eventType, sub.getOrgId(),
                    eventCreatedAt, sub.getLastWebhookEventAt());
            return;
        }

        // SYSTEM 19 TASK 19.1: every subscription.* event carries the full current entity, same
        // as Stripe's customer.subscription.updated -- sync plan/currentPeriodEnd from it on every
        // delivery, not just the ones that also change status, so a Razorpay org's local
        // subscription-state record stays in step the same way a Stripe org's already does via
        // handleSubscriptionUpserted.
        syncSubscriptionState(sub, subscriptionEntity);
        if (eventCreatedAt != null) {
            sub.setLastWebhookEventAt(eventCreatedAt);
        }

        switch (eventType) {
            case "subscription.activated", "subscription.charged" -> handleRecovered(sub);
            case "subscription.pending" -> handlePastDue(sub, "Razorpay retry in progress");
            case "subscription.halted" -> handlePastDue(sub, "Razorpay retries exhausted");
            case "subscription.cancelled", "subscription.completed" -> handleCancelled(sub);
            default -> log.debug("Unhandled Razorpay subscription event type: {}", eventType);
        }

        if (paymentEntity != null) {
            PlatformInvoice invoiceRow = mirrorInvoice(sub, subscriptionEntity, paymentEntity);
            mirrorPayment(sub, paymentEntity, invoiceRow);
        }
    }

    /**
     * Full-snapshot sync of the subscription-state fields Stripe's handleSubscriptionUpserted
     * already keeps current (plan, currentPeriodEnd) -- these were never touched anywhere in this
     * class before TASK 19.1, so a Razorpay org's plan/period would silently go stale the moment
     * it diverged from whatever was set at checkout. cancelAtPeriodEnd is deliberately NOT synced
     * here -- see this class's javadoc for why it is set synchronously at the API-call site
     * instead of parsed from this entity.
     */
    private void syncSubscriptionState(OrgSubscription sub, JSONObject subscriptionEntity) {
        String planId = subscriptionEntity.optString("plan_id", null);
        if (planId != null) {
            sub.setPlan(resolvePlan(planId));
        }
        long currentEnd = subscriptionEntity.optLong("current_end", 0L);
        if (currentEnd > 0) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(currentEnd));
        }
    }

    /**
     * SYSTEM 19 TASK 19.4: exposed for {@code BillingReconciliationJob}, mirroring
     * {@code StripeWebhookService.mapStatus}'s role there. Razorpay's raw subscription-entity
     * {@code status} field (distinct from the event-name-driven dispatch in
     * {@link #handleSubscriptionEvent}) per Razorpay's public docs as of this codebase's authoring
     * date -- same verify-before-trusting-in-production caveat as the rest of this class.
     * {@code created}/{@code authenticated} (pre-first-payment) intentionally map to {@code null}
     * rather than a guessed local status: this app has no local status for "not yet paid for" that
     * isn't already covered some other way (TRIAL), and guessing wrong here would make the
     * reconciliation job alert on every org still going through Razorpay's checkout flow.
     */
    public static OrgSubscription.Status mapStatus(String razorpayStatus) {
        return switch (razorpayStatus) {
            case "active" -> OrgSubscription.Status.ACTIVE;
            case "pending", "halted" -> OrgSubscription.Status.PAST_DUE;
            case "cancelled", "completed", "expired" -> OrgSubscription.Status.CANCELLED;
            default -> null;
        };
    }

    /** Mirrors {@code StripeWebhookService.resolvePlan}'s fallback behavior exactly: an
     *  unrecognized plan id defaults to STARTER with a warning rather than leaving the org's plan
     *  silently unset. */
    private OrgSubscription.Plan resolvePlan(String planId) {
        if (planId.equals(razorpayConfig.getPlanGrowth())) return OrgSubscription.Plan.GROWTH;
        if (planId.equals(razorpayConfig.getPlanEnterprise())) return OrgSubscription.Plan.ENTERPRISE;
        if (planId.equals(razorpayConfig.getPlanStarter())) return OrgSubscription.Plan.STARTER;
        log.warn("Unrecognized Razorpay plan id {}, defaulting to STARTER", planId);
        return OrgSubscription.Plan.STARTER;
    }

    /**
     * Mirrors a {@link Payment} row from {@code payload.payment.entity} (Billing Ledger design
     * doc §24), keyed on the payment's own id so repeat deliveries for the same payment (e.g. a
     * webhook retry) upsert in place. Linked to {@code invoiceRow} (from {@link #mirrorInvoice},
     * called first) the same way {@code StripeWebhookService.mirrorPayment} links to its own
     * upserted invoice -- so refunds can resolve which invoice a Razorpay payment settled the
     * same way they already do for Stripe.
     * <p>
     * Field names ({@code amount}, {@code currency}, {@code status}, {@code method},
     * {@code error_description}) follow Razorpay's public Payments API docs as of this codebase's
     * authoring date -- same unverified-against-a-live-payload caveat as the rest of this class
     * and {@link com.recoverpro.server.service.impl.RazorpayPaymentProvider}.
     */
    private void mirrorPayment(OrgSubscription sub, JSONObject paymentEntity, PlatformInvoice invoiceRow) {
        String providerPaymentId = paymentEntity.optString("id", null);
        if (providerPaymentId == null) {
            return;
        }
        Payment payment = paymentRepository
                .findByProviderAndProviderPaymentId(PaymentProviderType.RAZORPAY, providerPaymentId)
                .orElseGet(() -> Payment.builder()
                        .provider(PaymentProviderType.RAZORPAY)
                        .providerPaymentId(providerPaymentId)
                        .build());
        String status = paymentEntity.optString("status", "unknown");
        payment.setOrganizationId(sub.getOrgId());
        payment.setInvoiceId(invoiceRow == null ? null : invoiceRow.getId());
        payment.setAmountMinorUnits(paymentEntity.optLong("amount", 0L));
        payment.setCurrency(paymentEntity.optString("currency", "INR"));
        payment.setStatus(status);
        payment.setPaymentMethodType(paymentEntity.isNull("method") ? null : paymentEntity.optString("method", null));
        if ("captured".equals(status) && payment.getCapturedAt() == null) {
            payment.setCapturedAt(Instant.now());
        }
        if ("failed".equals(status) && payment.getFailedAt() == null) {
            payment.setFailedAt(Instant.now());
            payment.setFailureReason(paymentEntity.isNull("error_description")
                    ? null : paymentEntity.optString("error_description", null));
        }
        paymentRepository.save(payment);
        log.info("Razorpay payment mirrored: org={}, payment={}, status={}",
                sub.getOrgId(), providerPaymentId, status);
    }

    /**
     * SYSTEM 19 TASK 19.1: synthesizes a {@link PlatformInvoice} row from the subscription +
     * payment webhook entities -- Razorpay Subscriptions has no separate invoice object to mirror
     * (unlike Stripe's {@code invoice.paid}), so the payment itself IS the billing event. Upserted
     * keyed on {@code (provider, providerInvoiceId)} using the payment's own id, same idempotent-
     * on-redelivery shape as {@code StripeWebhookService.upsertInvoice}.
     * <p>
     * {@code status} is translated into Stripe's vocabulary ("paid"/"open"/"uncollectible") rather
     * than mirrored raw the way {@link Payment#getStatus()} deliberately is -- unlike Payment,
     * every {@code PlatformInvoiceRepository} revenue query string-matches {@code status = 'paid'}
     * directly, so a Razorpay row using Razorpay's own vocabulary ("captured") would silently
     * disappear from every collected-revenue figure instead of erroring.
     */
    private PlatformInvoice mirrorInvoice(OrgSubscription sub, JSONObject subscriptionEntity, JSONObject paymentEntity) {
        String providerPaymentId = paymentEntity.optString("id", null);
        if (providerPaymentId == null) {
            return null;
        }
        PlatformInvoice row = invoiceRepository.findByProviderInvoiceId(providerPaymentId)
                .orElseGet(() -> PlatformInvoice.builder()
                        .provider(PaymentProviderType.RAZORPAY)
                        .providerInvoiceId(providerPaymentId)
                        .build());

        String razorpayStatus = paymentEntity.optString("status", "unknown");
        String mirroredStatus = switch (razorpayStatus) {
            case "captured" -> "paid";
            case "failed" -> "uncollectible";
            default -> "open"; // created, authorized, refunded, ...
        };

        row.setOrgId(sub.getOrgId());
        row.setProviderCustomerId(subscriptionEntity.optString("customer_id", null));
        row.setStatus(mirroredStatus);
        long amount = paymentEntity.optLong("amount", 0L);
        row.setAmountDue(amount);
        row.setAmountPaid("paid".equals(mirroredStatus) ? amount : 0L);
        row.setCurrency(paymentEntity.optString("currency", "INR").toLowerCase());
        long currentStart = subscriptionEntity.optLong("current_start", 0L);
        long currentEnd = subscriptionEntity.optLong("current_end", 0L);
        if (currentStart > 0) row.setPeriodStart(Instant.ofEpochSecond(currentStart));
        if (currentEnd > 0) row.setPeriodEnd(Instant.ofEpochSecond(currentEnd));
        long createdAt = paymentEntity.optLong("created_at", 0L);
        row.setIssuedAt(createdAt > 0 ? Instant.ofEpochSecond(createdAt) : Instant.now());
        row.setProviderPaymentRef(providerPaymentId);
        // Razorpay Subscriptions payments have no hosted invoice page the way a Stripe Invoice
        // does -- subscription.short_url (set at checkout time) is a checkout link, not an
        // invoice/receipt page, so left null rather than reusing it for something it isn't.
        if ("paid".equals(mirroredStatus) && row.getPaidAt() == null) {
            row.setPaidAt(Instant.now());
        } else if (!"paid".equals(mirroredStatus)) {
            row.setPaidAt(null);
        }

        invoiceRepository.save(row);
        log.info("Razorpay invoice mirrored: org={}, payment={}, status={}, paid={} paise",
                sub.getOrgId(), providerPaymentId, mirroredStatus, row.getAmountPaid());
        return row;
    }

    private void handleRecovered(OrgSubscription sub) {
        boolean wasPastDue = sub.getStatus() == OrgSubscription.Status.PAST_DUE;
        sub.setStatus(OrgSubscription.Status.ACTIVE);
        sub.setPastDueSince(null);
        subscriptionRepository.save(sub);
        featureFlagService.provisionFlagsFor(sub);
        log.info("Razorpay subscription active: org={}", sub.getOrgId());
        // SYSTEM 19 TASK 19.1: parity with StripeWebhookService.handleSubscriptionUpserted, which
        // audits SUBSCRIPTION_CHANGED on every sync -- this path previously audited nothing at
        // all on ordinary activation/renewal, only on entering PAST_DUE or CANCELLED.
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.SUBSCRIPTION_CHANGED)
                .resourceType(AuditResourceType.SUBSCRIPTION)
                .resourceId(sub.getOrgId().toString())
                .actorTypeOverride(AuditActorType.SYSTEM)
                .organizationIdOverride(sub.getOrgId())
                .afterState(Map.of("status", sub.getStatus().name(), "plan", sub.getPlan().name()))
                .build());
        if (wasPastDue) {
            notificationService.createForOrgRole(sub.getOrgId(), PlatformConstants.ROLE_ORG_ADMIN,
                    NotificationType.ORG_PAYMENT_RECOVERED,
                    "Payment received", "Your subscription is active again -- thanks for settling up.");
        }
    }

    private void handlePastDue(OrgSubscription sub, String reason) {
        boolean enteringPastDue = sub.getStatus() != OrgSubscription.Status.PAST_DUE;
        if (enteringPastDue) {
            sub.setPastDueSince(Instant.now());
        }
        sub.setStatus(OrgSubscription.Status.PAST_DUE);
        subscriptionRepository.save(sub);
        featureFlagService.provisionFlagsFor(sub);
        log.warn("Razorpay subscription past due: org={}, reason={}", sub.getOrgId(), reason);
        if (enteringPastDue) {
            notificationService.createForOrgRole(sub.getOrgId(), PlatformConstants.ROLE_ORG_ADMIN,
                    NotificationType.ORG_PAYMENT_FAILED,
                    "Payment failed",
                    "We couldn't process your latest payment. We'll retry automatically -- "
                            + "please update your payment method to avoid any service interruption.");
        }
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.INVOICE_PAYMENT_FAILED)
                .resourceType(AuditResourceType.SUBSCRIPTION)
                .resourceId(sub.getOrgId().toString())
                .reason(reason)
                .actorTypeOverride(AuditActorType.SYSTEM)
                .organizationIdOverride(sub.getOrgId())
                .metadata(Map.of("razorpaySubscriptionId", String.valueOf(sub.getRazorpaySubscriptionId())))
                .build());
    }

    private void handleCancelled(OrgSubscription sub) {
        sub.setStatus(OrgSubscription.Status.CANCELLED);
        sub.setPastDueSince(null);
        subscriptionRepository.save(sub);
        featureFlagService.provisionFlagsFor(sub);
        log.info("Razorpay subscription cancelled: org={}", sub.getOrgId());
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.SUBSCRIPTION_CANCELLED)
                .resourceType(AuditResourceType.SUBSCRIPTION)
                .resourceId(sub.getOrgId().toString())
                .actorTypeOverride(AuditActorType.SYSTEM)
                .organizationIdOverride(sub.getOrgId())
                .build());
    }
}
