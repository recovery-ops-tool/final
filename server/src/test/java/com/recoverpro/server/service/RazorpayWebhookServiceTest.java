package com.recoverpro.server.service;

import com.recoverpro.server.config.RazorpayConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.entity.PlatformInvoice;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.PaymentRepository;
import com.recoverpro.server.repository.PlatformInvoiceRepository;
import com.recoverpro.server.repository.ProcessedRazorpayEventRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookServiceTest {

    @Mock private ProcessedRazorpayEventRepository processedEventRepository;
    @Mock private OrgSubscriptionRepository subscriptionRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PlatformInvoiceRepository invoiceRepository;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private RazorpayConfig razorpayConfig;

    private RazorpayWebhookService service;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        service = new RazorpayWebhookService(
                processedEventRepository, subscriptionRepository, paymentRepository, invoiceRepository,
                featureFlagService, auditService, notificationService, razorpayConfig);
        orgId = UUID.randomUUID();
        lenient().when(razorpayConfig.getPlanStarter()).thenReturn("plan_starter");
        lenient().when(razorpayConfig.getPlanGrowth()).thenReturn("plan_growth");
        lenient().when(razorpayConfig.getPlanEnterprise()).thenReturn("plan_enterprise");
    }

    private OrgSubscription subWith(OrgSubscription.Status status) {
        return OrgSubscription.builder()
                .orgId(orgId)
                .razorpaySubscriptionId("sub_xyz")
                .status(status)
                .plan(OrgSubscription.Plan.GROWTH)
                .build();
    }

    private JSONObject subscriptionEntity() {
        return new JSONObject().put("id", "sub_xyz");
    }

    @Test
    void handleSubscriptionEvent_charged_marksActiveAndNotifiesRecoveryIfWasPastDue() {
        OrgSubscription sub = subWith(OrgSubscription.Status.PAST_DUE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity());

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        assertThat(sub.getPastDueSince()).isNull();
        verify(featureFlagService).provisionFlagsFor(sub);
        verify(notificationService).createForOrgRole(eq(orgId), anyString(),
                eq(NotificationType.ORG_PAYMENT_RECOVERED), anyString(), anyString());
    }

    @Test
    void handleSubscriptionEvent_charged_alreadyActive_doesNotSpamRecoveryNotification() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity());

        verify(notificationService, never()).createForOrgRole(any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void handleSubscriptionEvent_pending_marksPastDueAndStampsClockOnce() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.pending", subscriptionEntity());

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.PAST_DUE);
        assertThat(sub.getPastDueSince()).isNotNull();
        verify(notificationService).createForOrgRole(eq(orgId), anyString(),
                eq(NotificationType.ORG_PAYMENT_FAILED), anyString(), anyString());
        verify(auditService).record(any());
    }

    @Test
    void handleSubscriptionEvent_halted_marksPastDueWithoutResettingExistingClock() {
        OrgSubscription sub = subWith(OrgSubscription.Status.PAST_DUE);
        java.time.Instant firstFailure = java.time.Instant.now().minusSeconds(86_400 * 3);
        sub.setPastDueSince(firstFailure);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.halted", subscriptionEntity());

        assertThat(sub.getPastDueSince()).isEqualTo(firstFailure);
        verify(notificationService, never()).createForOrgRole(any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void handleSubscriptionEvent_cancelled_marksCancelledAndAudits() {
        OrgSubscription sub = subWith(OrgSubscription.Status.PAST_DUE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.cancelled", subscriptionEntity());

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
        assertThat(sub.getPastDueSince()).isNull();
        verify(featureFlagService).provisionFlagsFor(sub);
        verify(auditService).record(any());
    }

    @Test
    void handleSubscriptionEvent_unknownSubscriptionId_isNoOp() {
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_unknown")).thenReturn(Optional.empty());

        service.handleSubscriptionEvent("subscription.charged", new JSONObject().put("id", "sub_unknown"));

        verify(subscriptionRepository, never()).save(any());
        verify(featureFlagService, never()).provisionFlagsFor(any());
    }

    @Test
    void handleSubscriptionEvent_missingSubscriptionId_isNoOp() {
        service.handleSubscriptionEvent("subscription.charged", new JSONObject());

        verify(subscriptionRepository, never()).findByRazorpaySubscriptionId(any());
    }

    /* ── Payment mirroring ───────────────────────────────────────────────── */

    @Test
    void handleSubscriptionEvent_chargedWithPaymentEntity_mirrorsCapturedPayment() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());

        JSONObject paymentEntity = new JSONObject()
                .put("id", "pay_1").put("amount", 299900L).put("currency", "INR")
                .put("status", "captured").put("method", "upi");

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity(), paymentEntity);

        com.recoverpro.server.entity.Payment saved = capturedPayment();
        assertThat(saved.getProvider()).isEqualTo(com.recoverpro.server.enums.PaymentProviderType.RAZORPAY);
        assertThat(saved.getProviderPaymentId()).isEqualTo("pay_1");
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getAmountMinorUnits()).isEqualTo(299900L);
        assertThat(saved.getPaymentMethodType()).isEqualTo("upi");
        assertThat(saved.getCapturedAt()).isNotNull();
    }

    @Test
    void handleSubscriptionEvent_paymentFailed_stampsFailureReason() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());

        JSONObject paymentEntity = new JSONObject()
                .put("id", "pay_2").put("amount", 299900L).put("currency", "INR")
                .put("status", "failed").put("error_description", "Insufficient funds");

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity(), paymentEntity);

        com.recoverpro.server.entity.Payment saved = capturedPayment();
        assertThat(saved.getFailedAt()).isNotNull();
        assertThat(saved.getFailureReason()).isEqualTo("Insufficient funds");
    }

    @Test
    void handleSubscriptionEvent_noPaymentEntity_skipsPaymentMirror() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity());

        verify(paymentRepository, never()).save(any());
    }

    private com.recoverpro.server.entity.Payment capturedPayment() {
        org.mockito.ArgumentCaptor<com.recoverpro.server.entity.Payment> captor =
                org.mockito.ArgumentCaptor.forClass(com.recoverpro.server.entity.Payment.class);
        verify(paymentRepository).save(captor.capture());
        return captor.getValue();
    }

    /* ── SYSTEM 19 TASK 19.1: invoice mirroring + subscription-state parity ─── */

    @Test
    void handleSubscriptionEvent_chargedWithPaymentEntity_mirrorsInvoiceRow() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());
        when(invoiceRepository.findByProviderInvoiceId("pay_1")).thenReturn(Optional.empty());

        JSONObject subEntity = subscriptionEntity().put("current_start", 1_700_000_000L).put("current_end", 1_702_600_000L);
        JSONObject paymentEntity = new JSONObject()
                .put("id", "pay_1").put("amount", 299900L).put("currency", "INR")
                .put("status", "captured").put("created_at", 1_700_000_100L);

        service.handleSubscriptionEvent("subscription.charged", subEntity, paymentEntity);

        PlatformInvoice saved = capturedInvoice();
        assertThat(saved.getProvider()).isEqualTo(com.recoverpro.server.enums.PaymentProviderType.RAZORPAY);
        assertThat(saved.getProviderInvoiceId()).isEqualTo("pay_1");
        assertThat(saved.getOrgId()).isEqualTo(orgId);
        assertThat(saved.getStatus()).isEqualTo("paid");
        assertThat(saved.getAmountPaid()).isEqualTo(299900L);
        assertThat(saved.getAmountDue()).isEqualTo(299900L);
        assertThat(saved.getPaidAt()).isNotNull();
        assertThat(saved.getPeriodStart()).isEqualTo(java.time.Instant.ofEpochSecond(1_700_000_000L));
        assertThat(saved.getPeriodEnd()).isEqualTo(java.time.Instant.ofEpochSecond(1_702_600_000L));
    }

    @Test
    void handleSubscriptionEvent_paymentFailed_mirrorsInvoiceAsUncollectibleNotPaid() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());
        when(invoiceRepository.findByProviderInvoiceId("pay_2")).thenReturn(Optional.empty());

        JSONObject paymentEntity = new JSONObject()
                .put("id", "pay_2").put("amount", 299900L).put("currency", "INR").put("status", "failed");

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity(), paymentEntity);

        PlatformInvoice saved = capturedInvoice();
        assertThat(saved.getStatus()).isEqualTo("uncollectible");
        assertThat(saved.getAmountPaid()).isZero();
        assertThat(saved.getPaidAt()).isNull();
    }

    @Test
    void handleSubscriptionEvent_invoiceLinksBackToMirroredPayment() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));
        when(paymentRepository.findByProviderAndProviderPaymentId(any(), anyString())).thenReturn(Optional.empty());
        UUID existingInvoiceRowId = UUID.randomUUID();
        PlatformInvoice existingRow = PlatformInvoice.builder()
                .id(existingInvoiceRowId).providerInvoiceId("pay_3").orgId(orgId).status("open").build();
        when(invoiceRepository.findByProviderInvoiceId("pay_3")).thenReturn(Optional.of(existingRow));

        JSONObject paymentEntity = new JSONObject()
                .put("id", "pay_3").put("amount", 100000L).put("currency", "INR").put("status", "captured");

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity(), paymentEntity);

        // Proves mirrorInvoice() ran BEFORE mirrorPayment() and its result (the SAME row, by id)
        // was actually threaded through, not just independently both called.
        assertThat(capturedPayment().getInvoiceId()).isEqualTo(existingInvoiceRowId);
    }

    @Test
    void handleSubscriptionEvent_syncsPlanAndCurrentPeriodEndFromEveryDelivery() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        sub.setPlan(OrgSubscription.Plan.STARTER);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        JSONObject subEntity = subscriptionEntity()
                .put("plan_id", "plan_enterprise").put("current_end", 1_702_600_000L);

        service.handleSubscriptionEvent("subscription.charged", subEntity);

        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.ENTERPRISE);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(java.time.Instant.ofEpochSecond(1_702_600_000L));
    }

    @Test
    void handleSubscriptionEvent_unrecognizedPlanId_defaultsToStarterLikeStripeDoes() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.charged",
                subscriptionEntity().put("plan_id", "plan_totally_unknown"));

        assertThat(sub.getPlan()).isEqualTo(OrgSubscription.Plan.STARTER);
    }

    @Test
    void handleSubscriptionEvent_charged_activation_auditsSubscriptionChanged() {
        OrgSubscription sub = subWith(OrgSubscription.Status.TRIAL);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.charged", subscriptionEntity());

        // Previously handleRecovered() audited nothing at all on ordinary activation -- only
        // PAST_DUE/CANCELLED transitions ever reached auditService.
        verify(auditService).record(argThat(req ->
                req.getAction() == com.recoverpro.server.enums.AuditAction.SUBSCRIPTION_CHANGED));
    }

    @Test
    void handleSubscriptionEvent_completed_treatedAsCancelled() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.completed", subscriptionEntity());

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
    }

    /* ── SYSTEM 19 TASK 19.3.c: out-of-order webhook protection ─────────────── */

    @Test
    void handleSubscriptionEvent_staleEventOlderThanLastApplied_doesNotRegressState() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        sub.setLastWebhookEventAt(java.time.Instant.ofEpochSecond(2_000_000_000L));
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.cancelled", subscriptionEntity(),
                null, java.time.Instant.ofEpochSecond(1_999_999_000L));

        assertThat(sub.getStatus())
                .as("a stale event must not regress state a newer event already advanced past")
                .isEqualTo(OrgSubscription.Status.ACTIVE);
        verify(subscriptionRepository, never()).save(sub);
    }

    @Test
    void handleSubscriptionEvent_newerEventAfterOlder_appliesAndAdvancesClock() {
        OrgSubscription sub = subWith(OrgSubscription.Status.ACTIVE);
        sub.setLastWebhookEventAt(java.time.Instant.ofEpochSecond(1_000_000_000L));
        when(subscriptionRepository.findByRazorpaySubscriptionId("sub_xyz")).thenReturn(Optional.of(sub));

        service.handleSubscriptionEvent("subscription.cancelled", subscriptionEntity(),
                null, java.time.Instant.ofEpochSecond(1_000_000_500L));

        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.CANCELLED);
        assertThat(sub.getLastWebhookEventAt()).isEqualTo(java.time.Instant.ofEpochSecond(1_000_000_500L));
    }

    private PlatformInvoice capturedInvoice() {
        org.mockito.ArgumentCaptor<PlatformInvoice> captor = org.mockito.ArgumentCaptor.forClass(PlatformInvoice.class);
        verify(invoiceRepository).save(captor.capture());
        return captor.getValue();
    }
}
