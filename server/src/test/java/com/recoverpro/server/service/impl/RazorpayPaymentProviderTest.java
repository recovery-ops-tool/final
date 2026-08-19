package com.recoverpro.server.service.impl;

import com.razorpay.Customer;
import com.razorpay.CustomerClient;
import com.razorpay.Refund;
import com.razorpay.PaymentClient;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Subscription;
import com.razorpay.SubscriptionClient;
import com.recoverpro.server.common.exception.PaymentProviderException;
import com.recoverpro.server.config.RazorpayConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.RefundResult;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RazorpayPaymentProviderTest {

    @Mock private OrgSubscriptionRepository subRepo;

    private RazorpayConfig config;
    private RazorpayClient client;
    private SubscriptionClient subscriptionClient;
    private CustomerClient customerClient;
    private PaymentClient paymentClient;
    private RazorpayPaymentProvider provider;
    private UUID orgId;

    @BeforeEach
    void setUp() throws Exception {
        orgId = UUID.randomUUID();
        config = new RazorpayConfig();
        setField(config, "planStarter", "plan_starter_123");
        setField(config, "planGrowth", "plan_growth_123");
        setField(config, "planEnterprise", "plan_enterprise_123");

        // RazorpayClient's constructor makes a real auth call, so it's mocked wholesale (allowed
        // by the inline mock-maker via Objenesis, which bypasses the constructor entirely) rather
        // than constructed for real -- same reasoning as never hitting a live gateway in a unit test.
        client = mock(RazorpayClient.class);
        subscriptionClient = mock(SubscriptionClient.class);
        customerClient = mock(CustomerClient.class);
        paymentClient = mock(PaymentClient.class);
        client.subscriptions = subscriptionClient;
        client.customers = customerClient;
        client.payments = paymentClient;
        setField(config, "client", client);

        provider = new RazorpayPaymentProvider(config, subRepo);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void requireClient_noCredentialsConfigured_throwsClearPaymentProviderException() {
        RazorpayConfig unconfigured = new RazorpayConfig();
        RazorpayPaymentProvider unconfiguredProvider = new RazorpayPaymentProvider(unconfigured, subRepo);

        // requireClient() is checked before any repository lookup in every provider method, so
        // an unconfigured client fails fast with a clear PaymentProviderException, not an NPE or
        // a misleading "subscription not found" error.
        assertThatThrownBy(() -> unconfiguredProvider.cancelSubscription(orgId, true))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("not configured");

        assertThatThrownBy(() -> unconfiguredProvider.refundPayment("pay_123", 1000L, "reason"))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void createCheckoutUrl_newOrg_createsCustomerAndSubscription_returnsShortUrl() throws RazorpayException {
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.empty());
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Customer customer = new Customer(new JSONObject().put("id", "cust_abc"));
        when(customerClient.create(any())).thenReturn(customer);

        Subscription subscription = new Subscription(new JSONObject()
                .put("id", "sub_xyz")
                .put("short_url", "https://rzp.io/i/abc123"));
        when(subscriptionClient.create(any())).thenReturn(subscription);

        String url = provider.createCheckoutUrl(orgId, "GROWTH");

        assertThat(url).isEqualTo("https://rzp.io/i/abc123");

        ArgumentCaptor<JSONObject> subReqCaptor = ArgumentCaptor.forClass(JSONObject.class);
        org.mockito.Mockito.verify(subscriptionClient).create(subReqCaptor.capture());
        assertThat(subReqCaptor.getValue().getString("plan_id")).isEqualTo("plan_growth_123");
    }

    @Test
    void cancelSubscription_noRazorpaySubscriptionLinked_throwsIllegalState() {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).razorpaySubscriptionId(null).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> provider.cancelSubscription(orgId, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Razorpay subscription linked");
    }

    @Test
    void cancelSubscription_atPeriodEnd_sendsCancelAtCycleEndTrue() throws RazorpayException {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).razorpaySubscriptionId("sub_xyz").build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subscriptionClient.cancel(org.mockito.ArgumentMatchers.eq("sub_xyz"), any()))
                .thenReturn(new Subscription(new JSONObject().put("id", "sub_xyz")));

        provider.cancelSubscription(orgId, true);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        org.mockito.Mockito.verify(subscriptionClient).cancel(org.mockito.ArgumentMatchers.eq("sub_xyz"), captor.capture());
        assertThat(captor.getValue().getInt("cancel_at_cycle_end")).isEqualTo(1);
    }

    /** SYSTEM 19 TASK 19.1: org_subscriptions.cancel_at_period_end was never persisted anywhere
     *  on the Razorpay path before this -- set synchronously from this call's own parameter, see
     *  the method's javadoc for why that is a deliberate exception to "webhooks are the source of
     *  truth" rather than an architecture violation. */
    @Test
    void cancelSubscription_atPeriodEnd_persistsCancelAtPeriodEndLocally() throws RazorpayException {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).razorpaySubscriptionId("sub_xyz")
                .cancelAtPeriodEnd(false).status(OrgSubscription.Status.ACTIVE).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subscriptionClient.cancel(org.mockito.ArgumentMatchers.eq("sub_xyz"), any()))
                .thenReturn(new Subscription(new JSONObject().put("id", "sub_xyz")));

        provider.cancelSubscription(orgId, true);

        assertThat(sub.getCancelAtPeriodEnd()).isTrue();
        // Status stays webhook-driven (subscription.cancelled is a reliable Razorpay event,
        // unlike the cancel-at-cycle-end flag) -- this call must not jump ahead of it.
        assertThat(sub.getStatus()).isEqualTo(OrgSubscription.Status.ACTIVE);
        org.mockito.Mockito.verify(subRepo).save(sub);
    }

    @Test
    void changePlan_noExistingRazorpaySubscription_throwsIllegalState() {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).razorpaySubscriptionId(null).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> provider.changePlan(orgId, "GROWTH", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No existing Razorpay subscription linked");
    }

    @Test
    void changePlan_upgrade_appliesNow() throws RazorpayException {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).razorpaySubscriptionId("sub_xyz").build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subscriptionClient.update(org.mockito.ArgumentMatchers.eq("sub_xyz"), any()))
                .thenReturn(new Subscription(new JSONObject().put("id", "sub_xyz")));

        provider.changePlan(orgId, "GROWTH", true);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        org.mockito.Mockito.verify(subscriptionClient).update(org.mockito.ArgumentMatchers.eq("sub_xyz"), captor.capture());
        assertThat(captor.getValue().getString("plan_id")).isEqualTo("plan_growth_123");
        assertThat(captor.getValue().getString("schedule_change_at")).isEqualTo("now");
    }

    @Test
    void changePlan_downgrade_deferredToCycleEnd() throws RazorpayException {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).razorpaySubscriptionId("sub_xyz").build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subscriptionClient.update(org.mockito.ArgumentMatchers.eq("sub_xyz"), any()))
                .thenReturn(new Subscription(new JSONObject().put("id", "sub_xyz")));

        provider.changePlan(orgId, "STARTER", false);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        org.mockito.Mockito.verify(subscriptionClient).update(org.mockito.ArgumentMatchers.eq("sub_xyz"), captor.capture());
        assertThat(captor.getValue().getString("plan_id")).isEqualTo("plan_starter_123");
        assertThat(captor.getValue().getString("schedule_change_at")).isEqualTo("cycle_end");
    }

    @Test
    void refundPayment_processedStatus_mapsToSucceededTrue() throws RazorpayException {
        Refund refund = new Refund(new JSONObject().put("id", "rfnd_1").put("status", "processed"));
        when(paymentClient.refund(org.mockito.ArgumentMatchers.eq("pay_123"), any())).thenReturn(refund);

        RefundResult result = provider.refundPayment("pay_123", 5000L, "billing correction");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.providerRefundId()).isEqualTo("rfnd_1");
        assertThat(result.status()).isEqualTo("processed");
    }

    @Test
    void refundPayment_pendingStatus_mapsToSucceededFalse() throws RazorpayException {
        Refund refund = new Refund(new JSONObject().put("id", "rfnd_2").put("status", "pending"));
        when(paymentClient.refund(org.mockito.ArgumentMatchers.eq("pay_456"), any())).thenReturn(refund);

        RefundResult result = provider.refundPayment("pay_456", 2000L, "partial refund");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.status()).isEqualTo("pending");
    }

    @Test
    void createPortalUrl_alwaysThrows_noRazorpayEquivalent() {
        assertThatThrownBy(() -> provider.createPortalUrl(orgId))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("Self-service billing management isn't available");
    }
}
