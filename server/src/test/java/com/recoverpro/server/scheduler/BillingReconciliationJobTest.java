package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.enums.PaymentProviderType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.OpsAlertService;
import com.recoverpro.server.service.PaymentProvider;
import com.recoverpro.server.service.PaymentProviderResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SYSTEM 19 TASK 19.4. "Manually desynchronizing a test org's local state triggers an alert
 *  within one job cycle" -- these tests are that scenario, driven directly (this job has no
 *  ShedLock proxy to unwrap; it is plain Mockito, not a Spring context). */
@ExtendWith(MockitoExtension.class)
class BillingReconciliationJobTest {

    @Mock private OrgSubscriptionRepository subscriptionRepository;
    @Mock private PaymentProviderResolver providerResolver;
    @Mock private OpsAlertService opsAlertService;
    @Mock private PaymentProvider stripeProvider;

    private BillingReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new BillingReconciliationJob(subscriptionRepository, providerResolver, opsAlertService);
    }

    private OrgSubscription stripeSub(OrgSubscription.Status localStatus) {
        return OrgSubscription.builder()
                .orgId(UUID.randomUUID())
                .provider(PaymentProviderType.STRIPE)
                .stripeSubscriptionId("sub_xyz")
                .status(localStatus)
                .build();
    }

    @Test
    void run_localActiveButProviderCancelled_alertsWithFullDetail() {
        OrgSubscription sub = stripeSub(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findAll()).thenReturn(List.of(sub));
        when(providerResolver.resolve(PaymentProviderType.STRIPE)).thenReturn(stripeProvider);
        when(stripeProvider.fetchRemoteStatus(sub.getOrgId())).thenReturn(Optional.of("canceled"));

        job.run();

        verify(opsAlertService).alertJobFailure(
                org.mockito.ArgumentMatchers.eq("BillingReconciliationJob.divergence"),
                argThat(msg -> msg.contains(sub.getOrgId().toString())
                        && msg.contains("sub_xyz")
                        && msg.contains("local=ACTIVE")
                        && msg.contains("provider=canceled")),
                any());
    }

    @Test
    void run_localAndProviderAgree_noAlert() {
        OrgSubscription sub = stripeSub(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findAll()).thenReturn(List.of(sub));
        when(providerResolver.resolve(PaymentProviderType.STRIPE)).thenReturn(stripeProvider);
        when(stripeProvider.fetchRemoteStatus(sub.getOrgId())).thenReturn(Optional.of("active"));

        job.run();

        verify(opsAlertService, never()).alertJobFailure(
                org.mockito.ArgumentMatchers.eq("BillingReconciliationJob.divergence"), anyString(), any());
    }

    @Test
    void run_noProviderSubscriptionLinked_skipsWithoutCallingProvider() {
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID()).provider(PaymentProviderType.STRIPE)
                .status(OrgSubscription.Status.TRIAL).build(); // no stripeSubscriptionId
        when(subscriptionRepository.findAll()).thenReturn(List.of(sub));

        job.run();

        verify(providerResolver, never()).resolve(any());
        verify(opsAlertService, never()).alertJobFailure(anyString(), anyString(), any());
    }

    @Test
    void run_unmappableProviderStatus_notTreatedAsDivergence() {
        // Razorpay "created"/"authenticated" (pre-activation) maps to null on purpose -- not
        // enough information to call it a divergence.
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(UUID.randomUUID()).provider(PaymentProviderType.RAZORPAY)
                .razorpaySubscriptionId("sub_rzp").status(OrgSubscription.Status.TRIAL).build();
        when(subscriptionRepository.findAll()).thenReturn(List.of(sub));
        PaymentProvider razorpayProvider = org.mockito.Mockito.mock(PaymentProvider.class);
        when(providerResolver.resolve(PaymentProviderType.RAZORPAY)).thenReturn(razorpayProvider);
        when(razorpayProvider.fetchRemoteStatus(sub.getOrgId())).thenReturn(Optional.of("created"));

        job.run();

        verify(opsAlertService, never()).alertJobFailure(
                org.mockito.ArgumentMatchers.eq("BillingReconciliationJob.divergence"), anyString(), any());
    }

    @Test
    void run_providerFetchThrows_alertsFetchFailureButDoesNotAbortOtherOrgs() {
        OrgSubscription failing = stripeSub(OrgSubscription.Status.ACTIVE);
        OrgSubscription healthy = stripeSub(OrgSubscription.Status.ACTIVE);
        when(subscriptionRepository.findAll()).thenReturn(List.of(failing, healthy));
        when(providerResolver.resolve(PaymentProviderType.STRIPE)).thenReturn(stripeProvider);
        when(stripeProvider.fetchRemoteStatus(failing.getOrgId()))
                .thenThrow(new RuntimeException("Stripe API error"));
        when(stripeProvider.fetchRemoteStatus(healthy.getOrgId())).thenReturn(Optional.of("active"));

        job.run();

        verify(opsAlertService).alertJobFailure(
                org.mockito.ArgumentMatchers.eq("BillingReconciliationJob.fetchFailures"), anyString(), any());
        // The healthy org after the failing one must still be checked, not skipped.
        verify(stripeProvider).fetchRemoteStatus(healthy.getOrgId());
    }
}
