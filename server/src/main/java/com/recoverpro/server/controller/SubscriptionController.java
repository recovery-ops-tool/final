package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.PaymentProviderException;
import com.recoverpro.server.dto.request.ChangePlanRequest;
import com.recoverpro.server.dto.request.CheckoutRequest;
import com.recoverpro.server.dto.response.SubscriptionResponse;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.entity.OrgSubscription.Plan;
import com.recoverpro.server.entity.OrgSubscription.Status;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.FeatureFlagService;
import com.recoverpro.server.service.PaymentProviderResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final OrgSubscriptionRepository subRepo;
    private final PaymentProviderResolver paymentProviderResolver;
    private final FeatureFlagService featureFlagService;
    private final AuditService auditService;

    private static final String ADMINS = Authz.ADMINS;
    private static final String ALL_STAFF = Authz.ALL_STAFF;

    @GetMapping
    @PreAuthorize(ALL_STAFF)
    public ResponseEntity<ApiResponse<SubscriptionResponse>> get(
            @AuthenticationPrincipal UserPrincipal caller) {

        UUID orgId = caller.getOrganizationId();
        OrgSubscription sub = subRepo.findByOrgId(orgId).orElse(null);

        if (sub == null) {
            return ResponseEntity.ok(ApiResponse.success(SubscriptionResponse.builder()
                    .status(Status.INACTIVE).plan(Plan.NONE)
                    .hasStripeCustomer(false).hasActiveSubscription(false).trialDaysLeft(0)
                    .build()));
        }

        int trialDaysLeft = 0;
        if (sub.getTrialEndsAt() != null && sub.getStatus() == Status.TRIAL) {
            trialDaysLeft = (int) Math.max(0, ChronoUnit.DAYS.between(Instant.now(), sub.getTrialEndsAt()));
        }

        boolean activeAccess = sub.getStatus() == Status.TRIAL
                || sub.getStatus() == Status.ACTIVE
                || sub.getStatus() == Status.PAST_DUE;

        return ResponseEntity.ok(ApiResponse.success(SubscriptionResponse.builder()
                .status(sub.getStatus()).plan(sub.getPlan())
                .trialEndsAt(sub.getTrialEndsAt())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .cancelAtPeriodEnd(sub.getCancelAtPeriodEnd())
                .hasStripeCustomer(sub.getStripeCustomerId() != null)
                .hasActiveSubscription(activeAccess)
                .trialDaysLeft(trialDaysLeft)
                .build()));
    }

    @PostMapping("/free")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<String>> selectFree(
            @AuthenticationPrincipal UserPrincipal caller) {

        UUID orgId = requireOrgContext(caller);
        OrgSubscription sub = subRepo.findByOrgId(orgId).orElseGet(() ->
                OrgSubscription.builder().orgId(orgId).build());
        sub.setPlan(Plan.STARTER);
        sub.setStatus(Status.ACTIVE);
        sub.setTrialEndsAt(null);
        sub.setCurrentPeriodEnd(null);
        sub.setCancelAtPeriodEnd(false);
        subRepo.save(sub);
        featureFlagService.provisionFlagsFor(sub);
        log.info("Org {} selected free Starter plan", orgId);
        return ResponseEntity.ok(ApiResponse.success("Free plan activated"));
    }

    @PostMapping("/checkout")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<Map<String, String>>> checkout(
            @Valid @RequestBody CheckoutRequest body,
            @AuthenticationPrincipal UserPrincipal caller) {

        String plan = body.getPlan();
        UUID orgId = requireOrgContext(caller);
        try {
            String url = paymentProviderResolver.resolveForOrg(orgId).createCheckoutUrl(orgId, plan);
            return ResponseEntity.ok(ApiResponse.success(Map.of("url", url)));
        } catch (PaymentProviderException e) {
            log.error("Checkout error for org {}: {}", orgId, e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.of(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.of(e.getMessage(), null));
        }
    }

    /**
     * Changes an EXISTING subscription's plan, distinct from {@code /checkout} (which is only
     * for a brand-new subscription and would create a duplicate one if used against an org
     * that's already subscribed). Body: {@code plan} (required, e.g. "GROWTH").
     * <p>
     * Upgrade/downgrade is determined by {@link Plan}'s own declared ordinal order
     * (NONE &lt; STARTER &lt; GROWTH &lt; ENTERPRISE) -- policy and provider-specific timing
     * differences are documented on {@link com.recoverpro.server.service.PaymentProvider#changePlan}.
     */
    @PutMapping("/plan")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<String>> changePlan(
            @Valid @RequestBody ChangePlanRequest body,
            @AuthenticationPrincipal UserPrincipal caller) {

        UUID orgId = requireOrgContext(caller);
        Plan newPlan;
        try {
            newPlan = Plan.valueOf(body.getPlan().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unknown plan: " + body.getPlan());
        }

        OrgSubscription sub = subRepo.findByOrgId(orgId)
                .orElseThrow(() -> new BusinessException(
                        "No existing subscription to change -- use /checkout to start one."));
        Plan previousPlan = sub.getPlan();
        if (newPlan == previousPlan) {
            throw new BusinessException("Already on the " + newPlan + " plan");
        }
        boolean upgrade = newPlan.ordinal() > previousPlan.ordinal();

        try {
            paymentProviderResolver.resolveForOrg(orgId).changePlan(orgId, body.getPlan(), upgrade);
        } catch (PaymentProviderException e) {
            log.error("Plan-change error for org {}: {}", orgId, e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.of(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.of(e.getMessage(), null));
        }

        // The provider is the source of truth for plan/status (StripeWebhookService/
        // RazorpayWebhookService sync OrgSubscription from the resulting webhook) -- this audit
        // record captures who REQUESTED the change and when, not a claim that it already applied.
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.SUBSCRIPTION_CHANGED)
                .resourceType(AuditResourceType.SUBSCRIPTION)
                .resourceId(orgId.toString())
                .beforeState(Map.of("plan", previousPlan.name()))
                .afterState(Map.of("plan", newPlan.name()))
                .metadata(Map.of("upgrade", String.valueOf(upgrade)))
                .build());

        log.info("Org {} requested plan change {} -> {} (upgrade={})", orgId, previousPlan, newPlan, upgrade);
        return ResponseEntity.ok(ApiResponse.success("Plan change requested"));
    }

    @PostMapping("/portal")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<Map<String, String>>> portal(
            @AuthenticationPrincipal UserPrincipal caller) {

        UUID orgId = requireOrgContext(caller);
        try {
            String url = paymentProviderResolver.resolveForOrg(orgId).createPortalUrl(orgId);
            return ResponseEntity.ok(ApiResponse.success(Map.of("url", url)));
        } catch (PaymentProviderException e) {
            log.error("Portal error for org {}: {}", orgId, e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.of(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.of(e.getMessage(), null));
        }
    }

    /**
     * Every write endpoint here assumes a real tenant (org_subscriptions.org_id is NOT NULL, and
     * StripeService.ensureCustomer calls orgId.toString() unconditionally) -- but a platform admin's
     * organizationId is always null, since they don't belong to a tenant. Before this check,
     * selectFree() threw a raw DataIntegrityViolationException (NOT NULL constraint), checkout()
     * threw an unhandled NullPointerException, and portal() threw a caught-but-ugly
     * IllegalStateException("No subscription found for org: null") -- none of them a clean, readable
     * error. Platform admins manage a tenant's billing through PlatformSubscriptionController's
     * explicit-orgId endpoints instead; this controller is self-service for a real org's own staff.
     */
    private static UUID requireOrgContext(UserPrincipal caller) {
        UUID orgId = caller.getOrganizationId();
        if (orgId == null) {
            throw new BusinessException(
                    "Platform admins have no organization context for self-service billing. "
                            + "Manage a tenant's subscription via the platform billing console instead.");
        }
        return orgId;
    }
}
