package com.recoverpro.server.controller;

import com.recoverpro.server.config.StripeConfig;
import com.recoverpro.server.service.OpsAlertService;
import com.recoverpro.server.service.StripeWebhookService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Public route -- Stripe calls this directly, no JWT. Not covered by
 * {@code @PreAuthorize}; when a real JWT filter chain is wired into
 * SecurityConfig this path must stay excluded from auth requirements.
 *
 * Raw request bytes are read via {@link HttpServletRequest} rather than a
 * {@code @RequestBody String} parameter, because Spring's default
 * {@code StringHttpMessageConverter} only advertises {@code text/plain} and
 * would reject Stripe's {@code application/json} body before this method runs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeConfig stripeConfig;
    private final StripeWebhookService stripeWebhookService;
    private final OpsAlertService opsAlertService;
    private final MeterRegistry meterRegistry;

    @PostMapping
    public ResponseEntity<String> handle(
            HttpServletRequest request,
            @RequestHeader("Stripe-Signature") String sigHeader) throws IOException {

        String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            webhookCounter("invalid_signature").increment();
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        if (!stripeWebhookService.claimEvent(event.getId(), event.getType())) {
            webhookCounter("duplicate").increment();
            return ResponseEntity.ok("Duplicate event, already processed");
        }

        Optional<StripeObject> dataObject = event.getDataObjectDeserializer().getObject();
        if (dataObject.isEmpty()) {
            log.error("Could not deserialize Stripe event {} (type={}) -- API version mismatch",
                    event.getId(), event.getType());
            webhookCounter("deserialization_skipped").increment();
            return ResponseEntity.ok("Event acknowledged, deserialization skipped");
        }

        try {
            dispatch(event.getType(), dataObject.get(), event.getCreated());
        } catch (Exception e) {
            log.error("Error handling Stripe event {} (type={}): {}",
                    event.getId(), event.getType(), e.getMessage(), e);
            // Release the claim so Stripe's automatic retry (triggered by this non-200
            // response) can re-claim and re-attempt instead of hitting the "already
            // processed" short-circuit above and this failure being silently permanent.
            stripeWebhookService.releaseEventClaim(event.getId());
            // Money-critical: if Stripe's own retries also exhaust, this is the only signal an
            // operator gets that a subscription/payment never actually landed.
            opsAlertService.alertJobFailure("StripeWebhookController.dispatch",
                    "event=" + event.getId() + " type=" + event.getType(), e);
            webhookCounter("failed").increment();
            return ResponseEntity.status(500).body("Processing failed, will retry");
        }
        webhookCounter("processed").increment();
        return ResponseEntity.ok("Processed");
    }

    /** SYSTEM 12 TASK 12.2: payment_webhook_events_total{provider, outcome}. */
    private Counter webhookCounter(String outcome) {
        return Counter.builder("payment_webhook_events_total")
                .tag("provider", "stripe")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private void dispatch(String eventType, StripeObject dataObject, Long eventCreatedEpochSeconds) {
        Instant eventCreatedAt = eventCreatedEpochSeconds == null
                ? null : Instant.ofEpochSecond(eventCreatedEpochSeconds);
        switch (eventType) {
            case "checkout.session.completed" -> {
                if (dataObject instanceof Session session) {
                    stripeWebhookService.handleCheckoutCompleted(session);
                }
            }
            case "customer.subscription.created", "customer.subscription.updated" -> {
                if (dataObject instanceof Subscription subscription) {
                    stripeWebhookService.handleSubscriptionUpserted(subscription, eventCreatedAt);
                }
            }
            case "customer.subscription.deleted" -> {
                if (dataObject instanceof Subscription subscription) {
                    stripeWebhookService.handleSubscriptionDeleted(subscription, eventCreatedAt);
                }
            }
            case "invoice.paid" -> {
                if (dataObject instanceof Invoice invoice) {
                    stripeWebhookService.handleInvoicePaid(invoice);
                }
            }
            case "invoice.payment_failed" -> {
                if (dataObject instanceof Invoice invoice) {
                    stripeWebhookService.handleInvoicePaymentFailed(invoice);
                }
            }
            // Mirror-only events: these move no subscription state, they just keep
            // platform_invoices in step with Stripe so the billing console's history
            // and collected-revenue totals stay accurate.
            case "invoice.finalized", "invoice.voided", "invoice.marked_uncollectible" -> {
                if (dataObject instanceof Invoice invoice) {
                    stripeWebhookService.upsertInvoice(invoice);
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", eventType);
        }
    }
}
