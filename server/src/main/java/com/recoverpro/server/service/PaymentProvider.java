package com.recoverpro.server.service;

import com.recoverpro.server.common.exception.PaymentProviderException;

import java.util.UUID;

/**
 * Provider-agnostic surface for the operations RecoverPro's own SaaS billing actually needs
 * (Billing Ledger design doc §7) -- deliberately scoped to what {@code SubscriptionController}
 * uses plus the refund/cancel capability {@code Refund}/dunning need, not a speculative universal
 * payment interface. Kept at the same "org-level" altitude {@code StripeService} already uses
 * (methods take an org id, not a raw provider customer id) -- that's the existing design's own
 * abstraction level, not something invented for this interface.
 * <p>
 * Read-only reporting paths ({@code listInvoices}, used only by the platform-admin billing
 * console) are deliberately NOT part of this interface -- unifying a provider-specific SDK model
 * type into a generic wrapper buys little for a read path and isn't needed for either provider to
 * coexist correctly.
 */
public interface PaymentProvider {

    /** Starts a hosted checkout flow for a brand-new subscription; returns the URL to redirect
     *  the caller to. Implicitly creates a provider customer for the org if one doesn't exist
     *  yet. Calling this for an org that already has an active provider subscription creates a
     *  SECOND, separate subscription rather than modifying the existing one -- callers with an
     *  existing subscription must use {@link #changePlan} instead. */
    String createCheckoutUrl(UUID orgId, String planName) throws PaymentProviderException;

    /** Returns a hosted self-service billing management URL for an org that already has a
     *  provider customer. */
    String createPortalUrl(UUID orgId) throws PaymentProviderException;

    /** {@code atPeriodEnd = true} lets the current billing period run out before cancelling
     *  (the default, expected path); {@code false} cancels immediately. */
    void cancelSubscription(UUID orgId, boolean atPeriodEnd) throws PaymentProviderException;

    /**
     * Changes an org's EXISTING subscription to a different plan in place -- the counterpart to
     * {@link #createCheckoutUrl} for an org that's already subscribed. Policy (Billing Ledger
     * design doc §9/§10): upgrades apply immediately using the provider's own proration where it
     * has one; downgrades apply without a prorated credit for the unused higher-tier time, rather
     * than RecoverPro computing or crediting a manual proration amount itself.
     * <p>
     * {@code upgrade} does NOT mean identical timing across providers -- see each implementation's
     * javadoc for the real difference between what Stripe and Razorpay can actually do here; this
     * interface does not paper over a capability gap that genuinely exists.
     */
    void changePlan(UUID orgId, String newPlanName, boolean upgrade) throws PaymentProviderException;

    /** {@code providerPaymentRef} is the provider's own payment reference (a Stripe PaymentIntent
     *  id, a Razorpay payment id) -- callers resolve which payment a refund applies to before
     *  calling this; the interface doesn't know how to derive one from a local invoice id, since
     *  that mapping is provider-specific. */
    RefundResult refundPayment(String providerPaymentRef, Long amountMinorUnits, String reason)
            throws PaymentProviderException;

    /**
     * SYSTEM 19 TASK 19.4: the provider's own current, authoritative subscription status (Stripe's
     * raw string -- "active"/"past_due"/"canceled"/...; Razorpay's -- "active"/"halted"/
     * "cancelled"/...), fetched live rather than read from any local mirror. Used only by
     * {@code BillingReconciliationJob} to detect drift between what this app believes and what the
     * provider actually has on file -- deliberately returns the raw string, not this app's
     * {@code OrgSubscription.Status}, so the reconciliation job can log/alert with the exact value
     * a human would see if they opened the provider's own dashboard, not a lossy re-encoding of it.
     * Empty when the org has no provider subscription linked yet.
     */
    java.util.Optional<String> fetchRemoteStatus(UUID orgId) throws PaymentProviderException;
}
