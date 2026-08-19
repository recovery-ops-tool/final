package com.recoverpro.server.service;

import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.entity.FeatureFlag;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.FeatureFlagRepository;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 28.1: effectivePlan() used to map TRIAL straight to STARTER regardless of
 * trialEndsAt, so a trial whose clock had run out kept STARTER-level feature access until some
 * unrelated event happened to re-provision it. This asserts a provisioning call for an expired
 * trial correctly denies plan-gated features, and that a trial still within its window is
 * unaffected.
 */
@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock private FeatureFlagRepository repository;
    @Mock private StringRedisTemplate redis;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private OrgSubscriptionRepository orgSubscriptionRepository;

    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagService(repository, redis, auditLogService, auditService,
                orgSubscriptionRepository, new SimpleMeterRegistry());
        service.initStampedeGuard();
        // findGlobalByFlagKey is never reached here: every scenario below passes a real orgId,
        // and set()/setLimit() only consult the global row when organizationId is null.
        // lenient(): the PAST_DUE no-op case deliberately never reaches either of these calls
        // (that's the behavior under test), which strict stubbing would otherwise flag as unused.
        org.mockito.Mockito.lenient().when(repository.findByOrganizationIdAndFlagKey(any(), any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void provisionFlagsFor_expiredTrial_deniesPlanGatedFeatures() {
        UUID orgId = UUID.randomUUID();
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.STARTER)
                .trialEndsAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();

        service.provisionFlagsFor(sub);

        ArgumentCaptor<FeatureFlag> saved = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        List<FeatureFlag> lucienWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.LUCIEN_AI.equals(f.getFlagKey()))
                .toList();
        assertThat(lucienWrites).isNotEmpty();
        assertThat(lucienWrites.get(lucienWrites.size() - 1).getEnabled()).isFalse();

        List<FeatureFlag> maxUserWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.MAX_USERS.equals(f.getFlagKey()))
                .toList();
        assertThat(maxUserWrites).isNotEmpty();
        assertThat(maxUserWrites.get(maxUserWrites.size() - 1).getLimitValue()).isEqualTo(0L);
    }

    /**
     * SYSTEM-PLAN 20.1.d: the plan-matrix, override, and TRIAL-expiry branches of
     * {@code effectivePlan}/{@code provisionFlagsFor} were already covered above (SYSTEM 28); this
     * adds the remaining subscription-status branches TASK 20.1.d calls out by name -- ACTIVE and
     * CANCELLED -- plus PAST_DUE's deliberate no-op (see PAST_DUE test below).
     */
    @Test
    void provisionFlagsFor_active_grantsPlanFeatures() {
        UUID orgId = UUID.randomUUID();
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .status(OrgSubscription.Status.ACTIVE)
                .plan(OrgSubscription.Plan.GROWTH)
                .build();

        service.provisionFlagsFor(sub);

        ArgumentCaptor<FeatureFlag> saved = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        List<FeatureFlag> lucienWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.LUCIEN_AI.equals(f.getFlagKey()))
                .toList();
        assertThat(lucienWrites).isNotEmpty();
        assertThat(lucienWrites.get(lucienWrites.size() - 1).getEnabled()).isTrue();
    }

    @Test
    void provisionFlagsFor_cancelled_revokesPlanFeatures() {
        UUID orgId = UUID.randomUUID();
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .status(OrgSubscription.Status.CANCELLED)
                .plan(OrgSubscription.Plan.GROWTH)
                .build();

        service.provisionFlagsFor(sub);

        ArgumentCaptor<FeatureFlag> saved = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        List<FeatureFlag> lucienWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.LUCIEN_AI.equals(f.getFlagKey()))
                .toList();
        assertThat(lucienWrites).isNotEmpty();
        assertThat(lucienWrites.get(lucienWrites.size() - 1).getEnabled()).isFalse();
    }

    /**
     * SYSTEM-PLAN 20.1: PAST_DUE deliberately resolves to a {@code null} effective plan, and
     * {@code provisionFlagsFor} returns immediately on a null effective plan -- so a PAST_DUE
     * subscription is a documented, intentional NO-OP that leaves whatever plan-derived flags
     * already existed untouched (see {@code DunningScheduler}'s own header comment: "Product
     * access during the grace period is unaffected by this job -- SubscriptionController already
     * treats PAST_DUE as active access"). This is not a bug -- SYSTEM 20's own tasklist entry was
     * corrected to match this shipped grace-period policy rather than the reverse. This test locks
     * that behavior in so a future change to {@code effectivePlan} that accidentally starts writing
     * flags on PAST_DUE gets caught, since that would silently end the grace period.
     */
    /**
     * SYSTEM-PLAN 20.1.d: "per-org overrides/grants... are expected to exist" -- this is that
     * mechanism ({@code OrgSubscription.compedPlan}/{@code compedUntil}, resolved by
     * {@code activeComp()}), and it takes priority even over PAST_DUE's own no-op: a live comp
     * grant provisions its plan's features regardless of billing status, which is the whole point
     * of a platform-admin comp (e.g. a customer dispute in progress shouldn't lose access just
     * because payment also happens to be stuck).
     */
    @Test
    void provisionFlagsFor_pastDueWithActiveComp_compOverridesGracePeriodNoOp() {
        UUID orgId = UUID.randomUUID();
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .status(OrgSubscription.Status.PAST_DUE)
                .plan(OrgSubscription.Plan.STARTER)
                .compedPlan(OrgSubscription.Plan.ENTERPRISE)
                .compedUntil(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        service.provisionFlagsFor(sub);

        ArgumentCaptor<FeatureFlag> saved = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        List<FeatureFlag> customIntegrationsWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.CUSTOM_INTEGRATIONS.equals(f.getFlagKey()))
                .toList();
        assertThat(customIntegrationsWrites).isNotEmpty();
        assertThat(customIntegrationsWrites.get(customIntegrationsWrites.size() - 1).getEnabled()).isTrue();
    }

    @Test
    void provisionFlagsFor_pastDue_isNoOpDuringGracePeriod() {
        UUID orgId = UUID.randomUUID();
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .status(OrgSubscription.Status.PAST_DUE)
                .plan(OrgSubscription.Plan.GROWTH)
                .build();

        service.provisionFlagsFor(sub);

        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * SYSTEM-PLAN 20.3: "a subscription cancellation revokes feature access on the very next
     * request". {@code set()} evicts the org-scoped Redis cache key synchronously, in the same
     * call that persists the new value -- so a stale cached "true" from before the change can
     * never be served again once this method returns. Simulates the org-scoped cache key
     * ({@code isEnabled} reads it) actually flipping from cached-true to cache-miss-then-DB-false
     * after {@code set()} runs, rather than just asserting {@code redis.delete} was called with
     * the right argument in isolation.
     */
    @Test
    void set_evictsCache_soNextEntitlementCheckReflectsChangeImmediately() {
        UUID orgId = UUID.randomUUID();
        String flagKey = PlanFeatureMatrix.LUCIEN_AI;
        String cacheKey = "recoverpro:flag:" + orgId + ":" + flagKey;

        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(cacheKey)).thenReturn("1"); // stale cached "enabled"

        assertThat(service.isEnabled(orgId, flagKey, false)).isTrue();

        service.set(orgId, flagKey, false, "disabling", null);

        verify(redis).delete(eq(cacheKey));

        // Cache miss after eviction -- next check must re-resolve from the DB, not the stale value.
        when(valueOps.get(cacheKey)).thenReturn(null);
        when(repository.findByOrganizationIdAndFlagKey(orgId, flagKey))
                .thenReturn(Optional.of(FeatureFlag.builder().flagKey(flagKey).enabled(false).build()));

        assertThat(service.isEnabled(orgId, flagKey, false)).isFalse();
    }

    /**
     * SYSTEM-PLAN 15.3.b: {@code isEnabled} is named explicitly in the task text ("feature-flag
     * resolution") as an expensive entry needing stampede protection. Unlike userDetails/
     * lucienContext/systemPrompts it isn't behind Spring's cache abstraction at all -- it's a
     * hand-rolled Redis-then-DB lookup (SYSTEM 20 TASK 20.3) -- so the fix is the in-process
     * {@code stampedeGuard} Caffeine layer added this session. This proves N callers racing on the
     * same never-before-cached org+flagKey collapse onto one DB read, not N.
     */
    @Test
    void isEnabled_concurrentColdKey_collapsesToOneDbResolution() throws Exception {
        UUID orgId = UUID.randomUUID();
        String flagKey = PlanFeatureMatrix.LUCIEN_AI;
        int threads = 10;

        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null); // always a Redis miss

        when(repository.findByOrganizationIdAndFlagKey(eq(orgId), eq(flagKey))).thenAnswer(inv -> {
            Thread.sleep(50); // widen the race window
            return Optional.of(FeatureFlag.builder().flagKey(flagKey).enabled(true).build());
        });

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.isEnabled(orgId, flagKey, false);
            }));
        }
        ready.await();
        go.countDown();

        for (Future<Boolean> f : futures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isTrue();
        }
        pool.shutdown();

        verify(repository, org.mockito.Mockito.times(1)).findByOrganizationIdAndFlagKey(eq(orgId), eq(flagKey));
    }

    @Test
    void provisionFlagsFor_trialStillActive_grantsStarterFeatures() {
        UUID orgId = UUID.randomUUID();
        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .status(OrgSubscription.Status.TRIAL)
                .plan(OrgSubscription.Plan.STARTER)
                .trialEndsAt(Instant.now().plus(5, ChronoUnit.DAYS))
                .build();

        service.provisionFlagsFor(sub);

        ArgumentCaptor<FeatureFlag> saved = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        // TRIAL maps to STARTER, and STARTER includes neither LUCIEN_AI nor ADVANCED_REPORTS
        // (only GROWTH+ does) -- so this asserts the trial-still-active path resolves the SAME
        // way it always did, i.e. the expiry fix changed nothing for a trial in its window.
        List<FeatureFlag> lucienWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.LUCIEN_AI.equals(f.getFlagKey()))
                .toList();
        assertThat(lucienWrites).isNotEmpty();
        assertThat(lucienWrites.get(lucienWrites.size() - 1).getEnabled()).isFalse();

        List<FeatureFlag> maxUserWrites = saved.getAllValues().stream()
                .filter(f -> PlanFeatureMatrix.MAX_USERS.equals(f.getFlagKey()))
                .toList();
        assertThat(maxUserWrites).isNotEmpty();
        assertThat(maxUserWrites.get(maxUserWrites.size() - 1).getLimitValue()).isEqualTo(5L);
    }
}
