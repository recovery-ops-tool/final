package com.recoverpro.server.controller;

import com.recoverpro.server.dto.request.ChangePlanRequest;
import com.recoverpro.server.dto.request.GrantCompRequest;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.PlatformInvoiceRepository;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.FeatureFlagService;
import com.recoverpro.server.service.RefundService;
import com.recoverpro.server.service.StripeService;
import com.recoverpro.server.service.StripeWebhookService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.tax.GstInvoiceLineItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage: grantComp/revokeComp/changePlan/backfillInvoices are the only
 * consequential platform-admin write actions in the codebase that had no UserActionAuditService
 * call at all -- everything else comparable (PlatformOrganizationController's org
 * create/update/delete/activate, PlatformAdminAccessGuard's whole design) writes a permanent,
 * queryable audit-log entry. These four only logged to the application log (ephemeral, not the
 * same compliance trail) despite granting free plan tiers or forcing entitlement changes.
 */
@ExtendWith(MockitoExtension.class)
class PlatformSubscriptionControllerTest {

    @Mock private OrgSubscriptionRepository subRepo;
    @Mock private OrganizationRepository orgRepo;
    @Mock private StripeService stripeService;
    @Mock private PlatformInvoiceRepository invoiceRepo;
    @Mock private StripeWebhookService stripeWebhookService;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private RefundService refundService;
    @Mock private GstInvoiceLineItemService gstInvoiceLineItemService;

    private PlatformSubscriptionController controller;
    private UUID orgId;
    private UserPrincipal caller;

    @BeforeEach
    void setUp() {
        controller = new PlatformSubscriptionController(
                subRepo, orgRepo, stripeService, invoiceRepo, stripeWebhookService, featureFlagService,
                auditLogService, auditService, refundService, gstInvoiceLineItemService);
        orgId = UUID.randomUUID();
        caller = mock(UserPrincipal.class);
        lenient().when(caller.getId()).thenReturn(UUID.randomUUID());
        lenient().when(orgRepo.findById(orgId)).thenReturn(
                Optional.of(Organization.builder().id(orgId).name("Test Org").build()));
        lenient().when(invoiceRepo.sumCollectedByOrg(orgId)).thenReturn(0L);
    }

    @Test
    void grantComp_writesAuditLogEntry() {
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.empty());
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GrantCompRequest req = new GrantCompRequest();
        req.setPlan("GROWTH");
        req.setReason("pilot customer");
        controller.grantComp(orgId, req, caller);

        verify(auditLogService).logUserAction(any(), eq("ORG_SUBSCRIPTION_COMPED"), contains("pilot customer"));
    }

    @Test
    void revokeComp_writesAuditLogEntry() {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).build();
        sub.setCompedPlan(OrgSubscription.Plan.GROWTH);
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.revokeComp(orgId, caller);

        verify(auditLogService).logUserAction(any(), eq("ORG_SUBSCRIPTION_COMP_REVOKED"), anyString());
    }

    @Test
    void changePlan_writesAuditLogEntry() {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChangePlanRequest req = new ChangePlanRequest();
        req.setPlan("STARTER");
        controller.changePlan(orgId, req, caller);

        verify(auditLogService).logUserAction(any(), eq("ORG_SUBSCRIPTION_PLAN_FORCED"), anyString());
    }

    @Test
    void backfillInvoices_writesAuditLogEntry() {
        when(subRepo.findAll()).thenReturn(List.of());

        controller.backfillInvoices(caller);

        verify(auditLogService).logUserAction(any(), eq("PLATFORM_INVOICE_BACKFILL"), anyString());
    }

    // ─── SYSTEM 18 TASK 18.2.a: trial extension ────────────────────────────────

    @Test
    void extendTrial_subscriptionInTrial_extendsFromExistingEndDateAndAudits() {
        java.time.Instant currentEnd = java.time.Instant.now().plusSeconds(3600); // 1h from now, still future
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId)
                .status(OrgSubscription.Status.TRIAL).trialEndsAt(currentEnd).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.recoverpro.server.dto.request.ExtendTrialRequest req =
                new com.recoverpro.server.dto.request.ExtendTrialRequest();
        req.setAdditionalDays(7);
        req.setReason("customer requested more evaluation time");

        controller.extendTrial(orgId, req, caller);

        // Compounds from the EXISTING end date, not from "now" -- extending twice must not lose
        // the first extension.
        assertThat(sub.getTrialEndsAt()).isEqualTo(currentEnd.plus(7, java.time.temporal.ChronoUnit.DAYS));
        verify(auditLogService).logUserAction(any(), eq("ORG_TRIAL_EXTENDED"),
                contains("customer requested more evaluation time"));
        verify(auditService).record(argThat(evt ->
                evt.getAction() == com.recoverpro.server.enums.AuditAction.ORG_TRIAL_EXTENDED));
    }

    @Test
    void extendTrial_subscriptionNotInTrial_throwsAndDoesNotSave() {
        OrgSubscription sub = OrgSubscription.builder().orgId(orgId)
                .status(OrgSubscription.Status.ACTIVE).build();
        when(subRepo.findByOrgId(orgId)).thenReturn(Optional.of(sub));

        com.recoverpro.server.dto.request.ExtendTrialRequest req =
                new com.recoverpro.server.dto.request.ExtendTrialRequest();
        req.setAdditionalDays(7);
        req.setReason("mistaken request");

        assertThatThrownBy(() -> controller.extendTrial(orgId, req, caller))
                .isInstanceOf(com.recoverpro.server.common.exception.BusinessException.class)
                .hasMessageContaining("TRIAL");
        verify(subRepo, never()).save(any());
    }
}
