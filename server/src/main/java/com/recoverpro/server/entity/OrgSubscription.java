package com.recoverpro.server.entity;

import com.recoverpro.server.enums.PaymentProviderType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_subscriptions", indexes = {
        @Index(name = "idx_sub_org_id", columnList = "org_id", unique = true),
        @Index(name = "idx_sub_stripe_customer", columnList = "stripe_customer_id"),
        @Index(name = "idx_sub_stripe_sub", columnList = "stripe_subscription_id"),
        @Index(name = "idx_sub_razorpay_customer", columnList = "razorpay_customer_id"),
        @Index(name = "idx_sub_razorpay_sub", columnList = "razorpay_subscription_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgSubscription {

    public enum Status {
        TRIAL, ACTIVE, PAST_DUE, CANCELLED, INACTIVE
    }

    public enum Plan {
        NONE, STARTER, GROWTH, ENTERPRISE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, unique = true)
    private UUID orgId;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(name = "razorpay_customer_id", length = 64)
    private String razorpayCustomerId;

    @Column(name = "razorpay_subscription_id", length = 64)
    private String razorpaySubscriptionId;

    /** Which gateway owns this org's billing. Defaults STRIPE for all pre-migration rows;
     *  new signups can be routed to RAZORPAY once Razorpay is live (Billing Ledger design doc
     *  migration path -- a deliberate, separate cutover decision, not flipped by any migration). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentProviderType provider = PaymentProviderType.STRIPE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.TRIAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Plan plan = Plan.NONE;

    /** Monthly amount (INR) synced from the Stripe Price object -- the single source of
     * truth for MRR/ARR, kept in step with real billing instead of a hardcoded map. */
    @Column(name = "plan_amount", precision = 12, scale = 2)
    private BigDecimal planAmount;

    /** GST identification number for this org, required before any GST-compliant invoice line
     *  item can be generated for them (see GstInvoiceLineItemService). Also the source of the
     *  org's place-of-supply state code -- see Gstin.stateCode(), not a separately stored field. */
    @Column(name = "gstin", length = 15)
    private String gstin;

    /** Legal entity name for GST invoicing purposes -- may differ from the org's display name. */
    @Column(name = "billing_legal_name", length = 255)
    private String billingLegalName;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end")
    @Builder.Default
    private Boolean cancelAtPeriodEnd = false;

    /** Set when status first becomes PAST_DUE, cleared on recovery back to ACTIVE. Drives
     *  DunningScheduler's grace-period countdown -- never reset by a repeat payment-retry
     *  failure within the same PAST_DUE episode, only by entering PAST_DUE from ACTIVE. */
    @Column(name = "past_due_since")
    private Instant pastDueSince;

    /** SYSTEM 19 TASK 19.3.c: timestamp of the last webhook event actually applied to this row
     *  (Stripe Event.created / Razorpay's envelope created_at), NOT this row's own updatedAt --
     *  those are different clocks (updatedAt also moves on unrelated platform-admin writes like a
     *  comp grant, which must not make an otherwise-newer webhook look "stale" by comparison).
     *  Null for any row that predates this column or has never received a webhook. */
    @Column(name = "last_webhook_event_at")
    private Instant lastWebhookEventAt;

    /* ── Admin-granted access ────────────────────────────────────────────────
     * Written only by the platform-admin comp endpoints. StripeWebhookService
     * must never touch these: they exist precisely so a grant survives the
     * webhook overwriting plan/status/current_period_end from Stripe. */

    @Enumerated(EnumType.STRING)
    @Column(name = "comped_plan", length = 20)
    private Plan compedPlan;

    /** Null with a comp set means open-ended. */
    @Column(name = "comped_until")
    private Instant compedUntil;

    @Column(name = "comped_reason", columnDefinition = "TEXT")
    private String compedReason;

    @Column(name = "comped_by")
    private UUID compedBy;

    @Column(name = "comped_at")
    private Instant compedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The comped plan if a grant is currently live, otherwise null.
     *
     * <p>Expiry is evaluated on read, so a lapsed comp stops applying the moment
     * it passes {@code compedUntil} without needing a sweep job to clear it.
     * That also means the row keeps its history -- who granted what, when and
     * why -- after the grant stops taking effect.
     */
    public Plan activeComp() {
        if (compedPlan == null) return null;
        if (compedUntil != null && !Instant.now().isBefore(compedUntil)) return null;
        return compedPlan;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
