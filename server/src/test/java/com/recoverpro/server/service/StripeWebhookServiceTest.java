package com.recoverpro.server.service;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.config.StripeConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.entity.PlatformInvoice;
import com.recoverpro.server.entity.ProcessedStripeEvent;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.PaymentRepository;
import com.recoverpro.server.repository.PlatformInvoiceRepository;
import com.recoverpro.server.repository.ProcessedStripeEventRepository;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    @Mock
    private ProcessedStripeEventRepository processedEventRepository;
    @Mock
    private OrgSubscriptionRepository subscriptionRepository;
    @Mock
    private PlatformInvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private FeatureFlagService featureFlagService;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;

    private StripeConfig stripeConfig;
    private StripeWebhookService webhookService;

    @BeforeEach
    void setUp() {
        stripeConfig = new StripeConfig();
        setField(stripeConfig, "priceStarter", "price_starter_123");
        setField(stripeConfig, "priceGrowth", "price_growth_123");
        setField(stripeConfig, "priceEnterprise", "price_enterprise_123");

        webhookService = new StripeWebhookService(
                processedEventRepository, subscriptionRepository, invoiceRepository, paymentRepository,
                featureFlagService, stripeConfig, auditService, notificationService);
    }

    @Test
    void claimEvent_returnsTrueOnFirstDelivery() {
        boolean claimed = webhookService.claimEvent("evt_1", "invoice.paid");

        assertThat(claimed).isTrue();
        verify(processedEventRepository).saveAndFlush(any(ProcessedStripeEvent.class));
    }

    @Test
    void claimEvent_returnsFalseOnDuplicateDelivery() {
        when(processedEventRepository.saveAndFlush(any(ProcessedStripeEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        boolean claimed = webhookService.claimEvent("evt_1", "invoice.paid");

        assertThat(claimed).isFalse();
    }

    @Test
    void releaseEventClaim_deletesTheClaimRow() {
        webhookService.releaseEventClaim("evt_1");

        verify(processedEventRepository).deleteById("evt_1");
    }

    @Test
    void handleCheckoutCompleted_activatesSubscriptionAndStoresSubscriptionId() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_1")
                .status(OrgSubscription.Status.TRIAL)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_1")).thenReturn(Optional.of(sub));

        Session session = new Session();
        session.setCustomer("cus_1");
        session.setSubscription("sub_1");

        webhookService.handleCheckoutCompleted(session);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        assertThat(sub.getStripeSubscriptionId()).isEqualTo("sub_1");
        verify(subscriptionRepository).save(sub);
    }

    @Test
    void handleCheckoutCompleted_throwsWhenNoMatchingSubscription() {
        when(subscriptionRepository.findByStripeCustomerId("cus_unknown")).thenReturn(Optional.empty());

        Session session = new Session();
        session.setCustomer("cus_unknown");

        assertThatThrownBy(() -> webhookService.handleCheckoutCompleted(session))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cus_unknown");
    }

    @Test
    void handleSubscriptionUpserted_syncsStatusPlanPeriodEndAndProvisionsFlags() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_2")
                .status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.NONE)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_2")).thenReturn(Optional.of(sub));

        Subscription subscription = subscriptionFixture(
                "sub_2", "cus_2", "active", "price_growth_123", 1_800_000_000L, true);

        webhookService.handleSubscriptionUpserted(subscription);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.GROWTH);
        assertThat(sub.getStripeSubscriptionId()).isEqualTo("sub_2");
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
        assertThat(sub.getCancelAtPeriodEnd()).isTrue();
        verify(featureFlagService).provisionFlagsFor(sub);
    }

    /** SYSTEM 19 TASK 19.3.c: Stripe does not guarantee delivery order. A stale delivery arriving
     *  AFTER a newer one already applied must not regress local state back to the older snapshot. */
    @Test
    void handleSubscriptionUpserted_staleEventOlderThanLastApplied_doesNotRegressState() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_2b")
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.GROWTH)
                .lastWebhookEventAt(Instant.ofEpochSecond(2_000_000_000L))
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_2b")).thenReturn(Optional.of(sub));

        // An OLDER event (STARTER, cancel-at-period-end) delivered late, after the ACTIVE/GROWTH
        // state above (from a newer event) has already been applied.
        Subscription staleSubscription = subscriptionFixture(
                "sub_2b", "cus_2b", "canceled", "price_starter_123", 1_700_000_000L, true);

        webhookService.handleSubscriptionUpserted(staleSubscription, Instant.ofEpochSecond(1_999_999_000L));

        assertThat(sub.getStatus())
                .as("a stale event must not regress status back from what a newer event already set")
                .isEqualTo(OrgSubscription.Status.ACTIVE);
        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.GROWTH);
        verify(subscriptionRepository, never()).save(sub);
    }

    @Test
    void handleSubscriptionUpserted_newerEventAfterOlder_appliesAndAdvancesTheClock() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_2c")
                .status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.NONE)
                .lastWebhookEventAt(Instant.ofEpochSecond(1_000_000_000L))
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_2c")).thenReturn(Optional.of(sub));

        Subscription newerSubscription = subscriptionFixture(
                "sub_2c", "cus_2c", "active", "price_growth_123", 1_800_000_000L, false);

        webhookService.handleSubscriptionUpserted(newerSubscription, Instant.ofEpochSecond(1_000_000_500L));

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.GROWTH);
        assertThat(sub.getLastWebhookEventAt()).isEqualTo(Instant.ofEpochSecond(1_000_000_500L));
    }

    @Test
    void handleSubscriptionUpserted_noEventTimestamp_alwaysAppliesLikeBeforeTask193() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_2d")
                .status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.NONE)
                .lastWebhookEventAt(Instant.ofEpochSecond(9_999_999_999L)) // "in the future"
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_2d")).thenReturn(Optional.of(sub));

        Subscription subscription = subscriptionFixture(
                "sub_2d", "cus_2d", "active", "price_growth_123", 1_800_000_000L, false);

        // The 1-arg overload (null timestamp) is what backfills/tests with no real event envelope
        // use -- it must keep unconditionally applying, not suddenly start rejecting everything.
        webhookService.handleSubscriptionUpserted(subscription);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
    }

    @Test
    void handleSubscriptionDeleted_marksCancelledAndProvisionsFlags() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_3")
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.STARTER)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_3")).thenReturn(Optional.of(sub));

        Subscription subscription = subscriptionFixture(
                "sub_3", "cus_3", "canceled", "price_starter_123", null, false);

        webhookService.handleSubscriptionDeleted(subscription);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
        verify(featureFlagService).provisionFlagsFor(sub);
    }

    @Test
    void handleInvoicePaid_recoversFromPastDue() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_4")
                .status(OrgSubscription.Status.PAST_DUE)
                .plan(OrgSubscription.Plan.STARTER)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_4")).thenReturn(Optional.of(sub));

        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_4");

        webhookService.handleInvoicePaid(invoice);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        verify(subscriptionRepository).save(sub);
    }

    /** SYSTEM 19 TASK 19.2.c / 19.3.a (reactivate after cancel): DunningScheduler's grace-period
     *  expiry only ever flips the LOCAL status to CANCELLED -- it never calls Stripe's own cancel
     *  API -- so Stripe can still legitimately fire invoice.paid for a subscription this app
     *  believes is dead. Access must come back immediately, not require a brand-new checkout. */
    @Test
    void handleInvoicePaid_recoversFromCancelled_notJustPastDue() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_4b")
                .status(OrgSubscription.Status.CANCELLED)
                .plan(OrgSubscription.Plan.STARTER)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_4b")).thenReturn(Optional.of(sub));

        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_4b");

        webhookService.handleInvoicePaid(invoice);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        verify(subscriptionRepository).save(sub);
    }

    @Test
    void handleInvoicePaid_noOpWhenNotPastDue() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_5")
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.STARTER)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_5")).thenReturn(Optional.of(sub));

        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_5");

        webhookService.handleInvoicePaid(invoice);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void handleInvoicePaymentFailed_marksPastDue() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_6")
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.GROWTH)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_6")).thenReturn(Optional.of(sub));

        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_6");

        webhookService.handleInvoicePaymentFailed(invoice);

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.PAST_DUE);
        assertThat(sub.getPastDueSince()).isNotNull();
        verify(featureFlagService).provisionFlagsFor(sub);
        verify(notificationService).createForOrgRole(eq(sub.getOrgId()), anyString(),
                eq(com.recoverpro.server.enums.NotificationType.ORG_PAYMENT_FAILED), anyString(), anyString());
    }

    @Test
    void handleInvoicePaymentFailed_alreadyPastDue_doesNotResetClockOrReNotify() {
        Instant firstFailure = Instant.now().minus(3, ChronoUnit.DAYS);
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_7")
                .status(OrgSubscription.Status.PAST_DUE)
                .plan(OrgSubscription.Plan.GROWTH)
                .pastDueSince(firstFailure)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_7")).thenReturn(Optional.of(sub));

        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_7");

        webhookService.handleInvoicePaymentFailed(invoice);

        assertThat(sub.getPastDueSince()).isEqualTo(firstFailure);
        verify(notificationService, never()).createForOrgRole(any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void handleSubscriptionUpserted_setsPlanAmountFromStripePriceUnitAmount() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_8")
                .plan(OrgSubscription.Plan.NONE)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_8")).thenReturn(Optional.of(sub));

        Subscription subscription = subscriptionFixture(
                "sub_8", "cus_8", "active", "price_growth_123", null, false, 799900L);

        webhookService.handleSubscriptionUpserted(subscription);

        assertThat(sub.getPlanAmount()).isEqualByComparingTo("7999.00");
    }

    @Test
    void handleSubscriptionUpserted_unrecognizedPriceDefaultsToStarter() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_7")
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_7")).thenReturn(Optional.of(sub));

        Subscription subscription = subscriptionFixture(
                "sub_7", "cus_7", "active", "price_unknown_999", null, false);

        webhookService.handleSubscriptionUpserted(subscription);

        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.STARTER);
    }

    /* ── Comps survive Stripe ────────────────────────────────────────────── */

    @Test
    void handleSubscriptionUpserted_overwritesStripeFieldsButLeavesCompIntact() {
        Instant compedAt = Instant.now().minus(2, ChronoUnit.DAYS);
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_20")
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.STARTER)
                .compedPlan(OrgSubscription.Plan.ENTERPRISE)
                .compedReason("Design partner")
                .compedAt(compedAt)
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_20")).thenReturn(Optional.of(sub));

        webhookService.handleSubscriptionUpserted(subscriptionFixture(
                "sub_20", "cus_20", "active", "price_growth_123", 1_800_000_000L, false));

        // Stripe owns these and is expected to overwrite them...
        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.GROWTH);
        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);

        // ...but the grant is a parallel channel. This is the entire reason comps
        // are separate columns: writing plan directly meant an admin grant vanished
        // on the next renewal or payment, silently and at an unpredictable time.
        assertThat(sub.getCompedPlan()).isEqualTo(OrgSubscription.Plan.ENTERPRISE);
        assertThat(sub.getCompedReason()).isEqualTo("Design partner");
        assertThat(sub.getCompedAt()).isEqualTo(compedAt);
        assertThat(sub.activeComp()).isEqualTo(OrgSubscription.Plan.ENTERPRISE);
    }

    @Test
    void handleSubscriptionDeleted_leavesCompIntactSoAccessOutlivesCancellation() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .stripeCustomerId("cus_21")
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.GROWTH)
                .compedPlan(OrgSubscription.Plan.GROWTH)
                .compedReason("Comped through migration")
                .build();
        when(subscriptionRepository.findByStripeCustomerId("cus_21")).thenReturn(Optional.of(sub));

        webhookService.handleSubscriptionDeleted(subscriptionFixture(
                "sub_21", "cus_21", "canceled", "price_growth_123", null, false));

        // An org that stopped paying but was explicitly comped keeps its access;
        // revoking it is a deliberate admin action, not a side effect of churn.
        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
        assertThat(sub.activeComp()).isEqualTo(OrgSubscription.Plan.GROWTH);
    }

    /* ── Invoice mirroring ───────────────────────────────────────────────── */

    @Test
    void upsertInvoice_mirrorsNewInvoiceWithStripeAmountsUnconverted() {
        UUID orgId = UUID.randomUUID();
        stubSubscription("cus_10", orgId);
        when(invoiceRepository.findByProviderInvoiceId("in_10")).thenReturn(Optional.empty());

        Invoice invoice = invoiceFixture("in_10", "cus_10", "paid", "ABCD-0001", 299900L, 299900L);
        invoice.setStatusTransitions(paidAt(1_800_000_000L));

        webhookService.upsertInvoice(invoice);

        PlatformInvoice saved = captureSavedInvoice();
        assertThat(saved.getOrgId()).isEqualTo(orgId);
        assertThat(saved.getProviderInvoiceId()).isEqualTo("in_10");
        assertThat(saved.getNumber()).isEqualTo("ABCD-0001");
        assertThat(saved.getStatus()).isEqualTo("paid");
        // Paise straight from Stripe -- a /100 anywhere on this path is a 100x revenue bug.
        assertThat(saved.getAmountPaid()).isEqualTo(299900L);
        assertThat(saved.getAmountDue()).isEqualTo(299900L);
        assertThat(saved.getPaidAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
    }

    @Test
    void upsertInvoice_updatesExistingRowInPlaceRatherThanDuplicating() {
        UUID orgId = UUID.randomUUID();
        stubSubscription("cus_11", orgId);

        PlatformInvoice existing = PlatformInvoice.builder()
                .id(UUID.randomUUID()).providerInvoiceId("in_11").orgId(orgId)
                .status("open").amountPaid(0L).build();
        when(invoiceRepository.findByProviderInvoiceId("in_11")).thenReturn(Optional.of(existing));

        Invoice invoice = invoiceFixture("in_11", "cus_11", "paid", "ABCD-0002", 299900L, 299900L);
        invoice.setStatusTransitions(paidAt(1_800_000_500L));

        webhookService.upsertInvoice(invoice);

        PlatformInvoice saved = captureSavedInvoice();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getStatus()).isEqualTo("paid");
        assertThat(saved.getAmountPaid()).isEqualTo(299900L);
    }

    @Test
    void upsertInvoice_clearsPaidAtWhenInvoiceIsVoided() {
        UUID orgId = UUID.randomUUID();
        stubSubscription("cus_12", orgId);

        PlatformInvoice existing = PlatformInvoice.builder()
                .id(UUID.randomUUID()).providerInvoiceId("in_12").orgId(orgId)
                .status("paid").amountPaid(299900L)
                .paidAt(Instant.ofEpochSecond(1_800_000_000L)).build();
        when(invoiceRepository.findByProviderInvoiceId("in_12")).thenReturn(Optional.of(existing));

        webhookService.upsertInvoice(
                invoiceFixture("in_12", "cus_12", "void", "ABCD-0003", 299900L, 0L));

        // paid_at drives every revenue sum, so a voided invoice must stop counting.
        PlatformInvoice saved = captureSavedInvoice();
        assertThat(saved.getStatus()).isEqualTo("void");
        assertThat(saved.getPaidAt()).isNull();
    }

    @Test
    void upsertInvoice_dropsInvoiceForUnknownCustomerWithoutThrowing() {
        when(subscriptionRepository.findByStripeCustomerId("cus_stranger")).thenReturn(Optional.empty());

        // Must not throw: raising here would make Stripe retry this event forever
        // for a customer that will never map to an org.
        webhookService.upsertInvoice(
                invoiceFixture("in_13", "cus_stranger", "paid", "X-1", 100L, 100L));

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void handleInvoicePaid_mirrorsInvoiceEvenWhenSubscriptionNeedsNoStatusChange() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID()).stripeCustomerId("cus_14")
                .status(OrgSubscription.Status.ACTIVE).plan(OrgSubscription.Plan.GROWTH).build();
        when(subscriptionRepository.findByStripeCustomerId("cus_14")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findByProviderInvoiceId("in_14")).thenReturn(Optional.empty());

        Invoice invoice = invoiceFixture("in_14", "cus_14", "paid", "ABCD-0004", 599900L, 599900L);
        invoice.setStatusTransitions(paidAt(1_800_001_000L));

        webhookService.handleInvoicePaid(invoice);

        // Revenue must still be recorded even though the subscription was already ACTIVE.
        verify(invoiceRepository).save(any(PlatformInvoice.class));
        verify(subscriptionRepository, never()).save(any());
    }

    /* ── Payment mirroring ───────────────────────────────────────────────── */

    @Test
    void upsertInvoice_paymentIntentPresent_mirrorsPaymentRow() {
        UUID orgId = UUID.randomUUID();
        stubSubscription("cus_20", orgId);
        when(invoiceRepository.findByProviderInvoiceId("in_20")).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(PlatformInvoice.class))).thenAnswer(inv -> {
            PlatformInvoice row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());

        Invoice invoice = invoiceFixture("in_20", "cus_20", "paid", "ABCD-0020", 299900L, 299900L);
        invoice.setPaymentIntent("pi_20");
        invoice.setStatusTransitions(paidAt(1_800_002_000L));

        webhookService.upsertInvoice(invoice);

        com.recoverpro.server.entity.Payment saved = capturedPayment();
        assertThat(saved.getProvider()).isEqualTo(com.recoverpro.server.enums.PaymentProviderType.STRIPE);
        assertThat(saved.getProviderPaymentId()).isEqualTo("pi_20");
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getAmountMinorUnits()).isEqualTo(299900L);
        assertThat(saved.getStatus()).isEqualTo("paid");
        assertThat(saved.getCapturedAt()).isEqualTo(Instant.ofEpochSecond(1_800_002_000L));
    }

    @Test
    void upsertInvoice_noPaymentIntentYet_skipsPaymentMirror() {
        UUID orgId = UUID.randomUUID();
        stubSubscription("cus_21b", orgId);
        when(invoiceRepository.findByProviderInvoiceId("in_21b")).thenReturn(Optional.empty());

        Invoice invoice = invoiceFixture("in_21b", "cus_21b", "open", "ABCD-0021", 299900L, 0L);

        webhookService.upsertInvoice(invoice);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void upsertInvoice_uncollectible_stampsFailedAtOnce() {
        UUID orgId = UUID.randomUUID();
        stubSubscription("cus_22", orgId);
        when(invoiceRepository.findByProviderInvoiceId("in_22")).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(PlatformInvoice.class))).thenAnswer(inv -> {
            PlatformInvoice row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());

        Invoice invoice = invoiceFixture("in_22", "cus_22", "uncollectible", "ABCD-0022", 299900L, 0L);
        invoice.setPaymentIntent("pi_22");

        webhookService.upsertInvoice(invoice);

        com.recoverpro.server.entity.Payment saved = capturedPayment();
        assertThat(saved.getStatus()).isEqualTo("uncollectible");
        assertThat(saved.getFailedAt()).isNotNull();
        assertThat(saved.getFailureReason()).isNotBlank();
    }

    private com.recoverpro.server.entity.Payment capturedPayment() {
        ArgumentCaptor<com.recoverpro.server.entity.Payment> captor =
                ArgumentCaptor.forClass(com.recoverpro.server.entity.Payment.class);
        verify(paymentRepository).save(captor.capture());
        return captor.getValue();
    }

    private void stubSubscription(String customerId, UUID orgId) {
        when(subscriptionRepository.findByStripeCustomerId(customerId)).thenReturn(Optional.of(
                OrgSubscription.builder().orgId(orgId).stripeCustomerId(customerId).build()));
    }

    private PlatformInvoice captureSavedInvoice() {
        ArgumentCaptor<PlatformInvoice> captor = ArgumentCaptor.forClass(PlatformInvoice.class);
        verify(invoiceRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Invoice.StatusTransitions paidAt(long epochSeconds) {
        Invoice.StatusTransitions t = new Invoice.StatusTransitions();
        t.setPaidAt(epochSeconds);
        return t;
    }

    private static Invoice invoiceFixture(String id, String customerId, String status,
                                          String number, Long amountDue, Long amountPaid) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        invoice.setCustomer(customerId);
        invoice.setStatus(status);
        invoice.setNumber(number);
        invoice.setAmountDue(amountDue);
        invoice.setAmountPaid(amountPaid);
        invoice.setCurrency("inr");
        invoice.setCreated(1_799_000_000L);
        invoice.setHostedInvoiceUrl("https://invoice.stripe.com/" + id);
        invoice.setInvoicePdf("https://invoice.stripe.com/" + id + "/pdf");
        return invoice;
    }

    private static Subscription subscriptionFixture(
            String id, String customerId, String status, String priceId,
            Long currentPeriodEnd, boolean cancelAtPeriodEnd) {
        return subscriptionFixture(id, customerId, status, priceId, currentPeriodEnd, cancelAtPeriodEnd, null);
    }

    private static Subscription subscriptionFixture(
            String id, String customerId, String status, String priceId,
            Long currentPeriodEnd, boolean cancelAtPeriodEnd, Long unitAmountCents) {

        Price price = new Price();
        price.setId(priceId);
        price.setUnitAmount(unitAmountCents);

        SubscriptionItem item = new SubscriptionItem();
        item.setPrice(price);

        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));

        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setCustomer(customerId);
        subscription.setStatus(status);
        subscription.setItems(items);
        subscription.setCurrentPeriodEnd(currentPeriodEnd);
        subscription.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        return subscription;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
