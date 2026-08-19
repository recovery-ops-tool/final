package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.StripeConfig;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.service.StripeService;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.param.SubscriptionUpdateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 19 TASK 19.3.a: cancel-at-period-end, immediate cancel, upgrade, and downgrade against
 * the real Stripe SDK call shape (verifying the actual {@link SubscriptionUpdateParams} sent, not
 * just that "some update happened"). {@code Subscription.retrieve} is a static SDK factory method
 * that hits the network -- mocked statically, same pattern as {@code GlobalExceptionHandlerTest}
 * uses for {@code Sentry}'s static API.
 */
@ExtendWith(MockitoExtension.class)
class StripePaymentProviderTest {

    @Mock private StripeService stripeService;
    @Mock private OrgSubscriptionRepository subRepo;
    @Mock private StripeConfig stripeConfig;
    @Mock private Subscription remoteSubscription;

    private StripePaymentProvider provider;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        provider = new StripePaymentProvider(stripeService, subRepo, stripeConfig);
        orgId = UUID.randomUUID();
        Mockito.lenient().when(stripeConfig.getPriceGrowth()).thenReturn("price_growth");
        Mockito.lenient().when(stripeConfig.getPriceStarter()).thenReturn("price_starter");
    }

    private OrgSubscription subWithStripeId() {
        return OrgSubscription.builder().orgId(orgId).stripeSubscriptionId("sub_xyz").build();
    }

    @Test
    void cancelSubscription_atPeriodEnd_setsCancelAtPeriodEndTrueWithoutCancellingImmediately() throws StripeException {
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(subWithStripeId()));

        try (MockedStatic<Subscription> statics = Mockito.mockStatic(Subscription.class)) {
            statics.when(() -> Subscription.retrieve("sub_xyz")).thenReturn(remoteSubscription);
            when(remoteSubscription.update(any(SubscriptionUpdateParams.class))).thenReturn(remoteSubscription);

            provider.cancelSubscription(orgId, true);

            ArgumentCaptor<SubscriptionUpdateParams> captor = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
            verify(remoteSubscription).update(captor.capture());
            assertThat(captor.getValue().getCancelAtPeriodEnd()).isTrue();
            verify(remoteSubscription, org.mockito.Mockito.never()).cancel();
        }
    }

    @Test
    void cancelSubscription_immediate_callsCancelDirectlyNotUpdate() throws StripeException {
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(subWithStripeId()));

        try (MockedStatic<Subscription> statics = Mockito.mockStatic(Subscription.class)) {
            statics.when(() -> Subscription.retrieve("sub_xyz")).thenReturn(remoteSubscription);

            provider.cancelSubscription(orgId, false);

            verify(remoteSubscription).cancel();
            verify(remoteSubscription, org.mockito.Mockito.never()).update(any(SubscriptionUpdateParams.class));
        }
    }

    @Test
    void changePlan_upgrade_usesCreateProrationsSoTheDifferenceIsChargedNotJustAppliedFree() throws StripeException {
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(subWithStripeId()));
        stubSubscriptionItems();

        try (MockedStatic<Subscription> statics = Mockito.mockStatic(Subscription.class)) {
            statics.when(() -> Subscription.retrieve("sub_xyz")).thenReturn(remoteSubscription);
            when(remoteSubscription.update(any(SubscriptionUpdateParams.class))).thenReturn(remoteSubscription);

            provider.changePlan(orgId, "GROWTH", true);

            ArgumentCaptor<SubscriptionUpdateParams> captor = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
            verify(remoteSubscription).update(captor.capture());
            assertThat(captor.getValue().getProrationBehavior())
                    .isEqualTo(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS);
        }
    }

    /** Per PaymentProvider#changePlan's documented policy: a downgrade applies the new price
     *  IMMEDIATELY (same API call, same moment) -- it is NOT deferred to period end. The only
     *  difference from an upgrade is proration billing: no prorated credit for the unused
     *  higher-tier time, not a delayed price change. */
    @Test
    void changePlan_downgrade_appliesImmediatelyWithNoProrationCredit() throws StripeException {
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(subWithStripeId()));
        stubSubscriptionItems();

        try (MockedStatic<Subscription> statics = Mockito.mockStatic(Subscription.class)) {
            statics.when(() -> Subscription.retrieve("sub_xyz")).thenReturn(remoteSubscription);
            when(remoteSubscription.update(any(SubscriptionUpdateParams.class))).thenReturn(remoteSubscription);

            provider.changePlan(orgId, "STARTER", false);

            ArgumentCaptor<SubscriptionUpdateParams> captor = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
            verify(remoteSubscription).update(captor.capture());
            SubscriptionUpdateParams params = captor.getValue();
            assertThat(params.getProrationBehavior()).isEqualTo(SubscriptionUpdateParams.ProrationBehavior.NONE);
            // The item's price is changed in THIS SAME call -- proves it is not scheduled for
            // later, just billed without a credit.
            assertThat(params.getItems()).hasSize(1);
            assertThat(params.getItems().get(0).getPrice()).isEqualTo("price_starter");
        }
    }

    private void stubSubscriptionItems() {
        SubscriptionItem item = new SubscriptionItem();
        item.setId("si_123");
        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));
        when(remoteSubscription.getItems()).thenReturn(items);
    }
}
