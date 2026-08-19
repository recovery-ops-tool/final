package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.PaymentProviderException;
import com.recoverpro.server.config.StripeConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.PaymentProvider;
import com.recoverpro.server.service.RefundResult;
import com.recoverpro.server.service.StripeService;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Thin adapter over the existing, working {@link StripeService} -- delegates checkout/portal
 * unchanged (zero new Stripe surface for those two), adds cancelSubscription/refundPayment as new
 * capability the app didn't have before (Billing Ledger design doc §7 phase 3: "wrap existing
 * Stripe code, zero behavior change" for what already existed, additive for what didn't).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripePaymentProvider implements PaymentProvider {

    private final StripeService stripeService;
    private final OrgSubscriptionRepository subRepo;
    private final StripeConfig stripeConfig;

    @Override
    public String createCheckoutUrl(UUID orgId, String planName) {
        try {
            return stripeService.createCheckoutUrl(orgId, planName);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe checkout error: " + e.getMessage(), e);
        }
    }

    @Override
    public String createPortalUrl(UUID orgId) {
        try {
            return stripeService.createPortalUrl(orgId);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe portal error: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelSubscription(UUID orgId, boolean atPeriodEnd) {
        OrgSubscription sub = subRepo.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalStateException("No subscription found for org: " + orgId));
        if (sub.getStripeSubscriptionId() == null) {
            throw new IllegalStateException("No Stripe subscription linked to org: " + orgId);
        }
        try {
            if (atPeriodEnd) {
                Subscription current = Subscription.retrieve(sub.getStripeSubscriptionId());
                current.update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build());
            } else {
                Subscription.retrieve(sub.getStripeSubscriptionId()).cancel();
            }
            log.info("Stripe subscription cancel requested: org={}, atPeriodEnd={}", orgId, atPeriodEnd);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe cancellation error: " + e.getMessage(), e);
        }
    }

    /**
     * Upgrade: {@code proration_behavior=create_prorations} -- Stripe computes and adds a
     * prorated invoice item for the difference automatically (billed on the next regular
     * invoice under this account's default invoicing settings; whether it's instead invoiced
     * immediately depends on account-level configuration this codebase has no visibility into
     * and does not assume). Downgrade: {@code proration_behavior=none} -- the price changes
     * immediately with no prorated credit for the unused higher-tier time, matching the
     * documented policy in {@link PaymentProvider#changePlan}.
     * <p>
     * Both API calls verified against the actual stripe-java 25.3.0 SDK (decompiled and read,
     * not assumed from memory) -- {@code Subscription.getItems().getData().get(0).getId()} for
     * the subscription item id, {@code SubscriptionUpdateParams.Item.builder().setId(...)
     * .setPrice(...)}, {@code ProrationBehavior.CREATE_PRORATIONS}/{@code .NONE}.
     */
    @Override
    public void changePlan(UUID orgId, String newPlanName, boolean upgrade) {
        OrgSubscription sub = subRepo.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalStateException("No subscription found for org: " + orgId));
        if (sub.getStripeSubscriptionId() == null) {
            throw new IllegalStateException(
                    "No existing Stripe subscription linked to org: " + orgId + " -- use checkout instead.");
        }
        try {
            Subscription current = Subscription.retrieve(sub.getStripeSubscriptionId());
            String itemId = current.getItems().getData().get(0).getId();
            String newPriceId = resolvePriceId(newPlanName);

            SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                    .addItem(SubscriptionUpdateParams.Item.builder()
                            .setId(itemId)
                            .setPrice(newPriceId)
                            .build())
                    .setProrationBehavior(upgrade
                            ? SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS
                            : SubscriptionUpdateParams.ProrationBehavior.NONE)
                    .build();
            current.update(params);
            log.info("Stripe subscription plan changed: org={}, newPlan={}, upgrade={}", orgId, newPlanName, upgrade);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe plan-change error: " + e.getMessage(), e);
        }
    }

    private String resolvePriceId(String planName) {
        return switch (planName.toUpperCase()) {
            case "GROWTH"     -> stripeConfig.getPriceGrowth();
            case "ENTERPRISE" -> stripeConfig.getPriceEnterprise();
            default           -> stripeConfig.getPriceStarter();
        };
    }

    @Override
    public java.util.Optional<String> fetchRemoteStatus(UUID orgId) {
        OrgSubscription sub = subRepo.findByOrgId(orgId).orElse(null);
        if (sub == null || sub.getStripeSubscriptionId() == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(Subscription.retrieve(sub.getStripeSubscriptionId()).getStatus());
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe status-fetch error: " + e.getMessage(), e);
        }
    }

    @Override
    public RefundResult refundPayment(String providerPaymentRef, Long amountMinorUnits, String reason) {
        // `reason` isn't sent to Stripe: its Reason enum is a fixed dispute-reporting category
        // (duplicate/fraudulent/requested_by_customer), not free text. The human-readable reason
        // stays in RecoverPro's own Refund.reason column and audit trail, which is what it's for.
        try {
            RefundCreateParams.Builder params = RefundCreateParams.builder()
                    .setPaymentIntent(providerPaymentRef)
                    .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
            if (amountMinorUnits != null) {
                params.setAmount(amountMinorUnits);
            }
            Refund refund = Refund.create(params.build());
            log.info("Stripe refund created: paymentIntent={}, refundId={}, status={}",
                    providerPaymentRef, refund.getId(), refund.getStatus());
            return new RefundResult(refund.getId(), "succeeded".equals(refund.getStatus()), refund.getStatus());
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe refund error: " + e.getMessage(), e);
        }
    }
}
