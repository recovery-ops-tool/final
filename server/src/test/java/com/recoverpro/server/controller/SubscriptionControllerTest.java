package com.recoverpro.server.controller;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.ChangePlanRequest;
import com.recoverpro.server.dto.request.CheckoutRequest;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.FeatureFlagService;
import com.recoverpro.server.service.PaymentProvider;
import com.recoverpro.server.service.PaymentProviderResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage: selectFree/checkout/portal all assumed a real org context
 * (caller.getOrganizationId() != null), which fails silently badly for a platform admin (whose org
 * is always null) -- selectFree threw a raw DataIntegrityViolationException (org_subscriptions.org_id
 * is NOT NULL), checkout threw an unhandled NullPointerException (StripeService.ensureCustomer calls
 * orgId.toString() unconditionally), and portal threw a caught-but-ugly IllegalStateException. All
 * three now fail fast with a clean, readable BusinessException instead.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionControllerTest {

    @Mock private OrgSubscriptionRepository subRepo;
    @Mock private PaymentProviderResolver paymentProviderResolver;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private AuditService auditService;

    private SubscriptionController newController() {
        return new SubscriptionController(subRepo, paymentProviderResolver, featureFlagService, auditService);
    }

    private UserPrincipal principalWithOrg(UUID orgId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getOrganizationId()).thenReturn(orgId);
        return p;
    }

    private static ChangePlanRequest changePlanReq(String plan) {
        ChangePlanRequest req = new ChangePlanRequest();
        req.setPlan(plan);
        return req;
    }

    private static CheckoutRequest checkoutReq(String plan) {
        CheckoutRequest req = new CheckoutRequest();
        req.setPlan(plan);
        return req;
    }

    @Test
    void selectFree_platformAdmin_throwsCleanBusinessException() {
        SubscriptionController controller = newController();
        UserPrincipal admin = principalWithOrg(null);

        assertThrows(BusinessException.class, () -> controller.selectFree(admin));

        verify(subRepo, never()).save(any());
    }

    @Test
    void selectFree_orgAdmin_succeeds() {
        SubscriptionController controller = newController();
        UUID orgId = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithOrg(orgId);
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.empty());
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.selectFree(orgAdmin);

        verify(subRepo).save(any(OrgSubscription.class));
    }

    @Test
    void checkout_platformAdmin_throwsCleanBusinessException() {
        SubscriptionController controller = newController();
        UserPrincipal admin = principalWithOrg(null);

        assertThrows(BusinessException.class, () -> controller.checkout(checkoutReq("GROWTH"), admin));
    }

    @Test
    void portal_platformAdmin_throwsCleanBusinessException() {
        SubscriptionController controller = newController();
        UserPrincipal admin = principalWithOrg(null);

        assertThrows(BusinessException.class, () -> controller.portal(admin));
    }

    @Test
    void changePlan_growthToEnterprise_detectedAsUpgrade() {
        SubscriptionController controller = newController();
        UUID orgId = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithOrg(orgId);
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).plan(OrgSubscription.Plan.GROWTH).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        PaymentProvider provider = mock(PaymentProvider.class);
        when(paymentProviderResolver.resolveForOrg(orgId)).thenReturn(provider);

        controller.changePlan(changePlanReq("ENTERPRISE"), orgAdmin);

        verify(provider).changePlan(orgId, "ENTERPRISE", true);
        verify(auditService).record(any());
    }

    @Test
    void changePlan_enterpriseToGrowth_detectedAsDowngrade() {
        SubscriptionController controller = newController();
        UUID orgId = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithOrg(orgId);
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).plan(OrgSubscription.Plan.ENTERPRISE).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        PaymentProvider provider = mock(PaymentProvider.class);
        when(paymentProviderResolver.resolveForOrg(orgId)).thenReturn(provider);

        controller.changePlan(changePlanReq("GROWTH"), orgAdmin);

        verify(provider).changePlan(orgId, "GROWTH", false);
    }

    @Test
    void changePlan_samePlanRequested_throwsWithoutCallingProvider() {
        SubscriptionController controller = newController();
        UUID orgId = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithOrg(orgId);
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).plan(OrgSubscription.Plan.GROWTH).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));

        assertThrows(BusinessException.class,
                () -> controller.changePlan(changePlanReq("GROWTH"), orgAdmin));

        verify(paymentProviderResolver, never()).resolveForOrg(any());
    }

    @Test
    void changePlan_noExistingSubscription_throwsGuidingToCheckout() {
        SubscriptionController controller = newController();
        UUID orgId = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithOrg(orgId);
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> controller.changePlan(changePlanReq("GROWTH"), orgAdmin));
    }

    @Test
    void changePlan_platformAdmin_throwsCleanBusinessException() {
        SubscriptionController controller = newController();
        UserPrincipal admin = principalWithOrg(null);

        assertThrows(BusinessException.class,
                () -> controller.changePlan(changePlanReq("GROWTH"), admin));
    }
}
