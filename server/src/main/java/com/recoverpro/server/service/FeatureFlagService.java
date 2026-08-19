package com.recoverpro.server.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.entity.FeatureFlag;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.repository.FeatureFlagRepository;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX  = "recoverpro:flag:";

    private static final Set<String> PROTECTED_COMPLIANCE_FLAGS = Set.of(
            "calling_hours_enabled",
            "audit_chain_enabled",
            "kyc_verification_enabled",
            "rbi_reporting_enabled",
            "dpdp_erasure_enabled"
    );

    private final FeatureFlagRepository repository;
    private final StringRedisTemplate redis;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final OrgSubscriptionRepository orgSubscriptionRepository;
    private final MeterRegistry meterRegistry;

    /**
     * TASK 15.3: {@code isEnabled} is on the request hot path (every {@code @RequiresFeature} check)
     * and, unlike {@code userDetails}/{@code lucienContext}/{@code systemPrompts}, isn't behind
     * Spring's cache abstraction at all -- it's hand-rolled against Redis (a deliberate SYSTEM 20
     * TASK 20.3 choice; see the field javadoc there). That means it gets none of Caffeine's atomic
     * get-or-compute for free. This is a small in-process single-flight layer in front of the same
     * Redis-then-DB resolution: concurrent callers for the same cold org+flagKey collapse onto one
     * computation via Caffeine's per-key atomicity, instead of each independently missing Redis and
     * hitting the DB. It is NOT a second source of truth for staleness -- {@link #evictCache} clears
     * this alongside the Redis key on every write, so nothing here outlives the write that
     * invalidated it. The 5s expiry is only a safety bound, not the correctness mechanism.
     *
     * <p>Keyed on organizationId+flagKey only (not {@code defaultIfMissing}): the sole caller,
     * {@code EntitlementServiceImpl.hasFeature()}, always passes {@code false}, so a single default
     * per key is safe in practice. A future caller passing a different default for the same key
     * would need this reworked.
     */
    private com.github.benmanes.caffeine.cache.Cache<String, Boolean> stampedeGuard;

    @PostConstruct
    void initStampedeGuard() {
        stampedeGuard = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(5))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, stampedeGuard, "featureFlagResolution");
    }

    public boolean isEnabled(UUID organizationId, String flagKey, boolean defaultIfMissing) {
        if (flagKey == null || flagKey.isBlank()) return defaultIfMissing;
        Boolean resolved = stampedeGuard.get(cacheKey(organizationId, flagKey), k -> {
            Boolean cached = readCached(organizationId, flagKey);
            if (cached != null) return cached;
            boolean fromDb = resolveFromDb(organizationId, flagKey, defaultIfMissing);
            writeCached(organizationId, flagKey, fromDb);
            return fromDb;
        });
        return resolved != null ? resolved : defaultIfMissing;
    }

    /**
     * Reads a numeric entitlement (e.g. {@code MAX_USERS}). Empty means unlimited -- either the
     * key was never a numeric-limit key for this plan (Enterprise), or no row exists at all.
     * Deliberately uncached, unlike {@link #isEnabled}: limit checks happen far less often than
     * routine feature gates, and reusing the boolean "1"/"0" Redis cache format for a numeric
     * value would require a second cache key scheme for no real benefit at RecoverPro's scale.
     */
    public java.util.Optional<Long> getLimit(UUID organizationId, String limitKey) {
        if (limitKey == null || limitKey.isBlank()) return java.util.Optional.empty();
        if (organizationId != null) {
            var tenant = repository.findByOrganizationIdAndFlagKey(organizationId, limitKey);
            if (tenant.isPresent() && tenant.get().getLimitValue() != null) {
                return java.util.Optional.of(tenant.get().getLimitValue());
            }
        }
        var global = repository.findGlobalByFlagKey(limitKey);
        if (global.isPresent() && global.get().getLimitValue() != null) {
            return java.util.Optional.of(global.get().getLimitValue());
        }
        return java.util.Optional.empty();
    }

    /** Writer counterpart to {@link #getLimit}. {@code limitValue == null} means unlimited. */
    @Transactional
    public FeatureFlag setLimit(UUID organizationId, String limitKey, Long limitValue,
                                String description, UUID actingUserId, FeatureFlag.FlagSource source) {
        FeatureFlag flag = (organizationId == null
                ? repository.findGlobalByFlagKey(limitKey)
                : repository.findByOrganizationIdAndFlagKey(organizationId, limitKey))
                .orElseGet(() -> FeatureFlag.builder()
                        .organizationId(organizationId)
                        .flagKey(limitKey)
                        .enabled(false)
                        .build());
        Long before = flag.getLimitValue();
        flag.setLimitValue(limitValue);
        if (description != null) flag.setDescription(description);
        flag.setUpdatedByUserId(actingUserId);
        flag.setSource(source);
        FeatureFlag saved = repository.save(flag);
        String scope = organizationId == null ? "GLOBAL" : organizationId.toString();
        if (actingUserId != null) {
            auditLogService.logUserAction(actingUserId, "FEATURE_FLAG_CHANGED",
                    "limit=" + limitKey + " org=" + scope + " before=" + before + " after=" + limitValue
                            + " source=" + source);
        }
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.FEATURE_FLAG_CHANGED)
                .resourceType(AuditResourceType.FEATURE_FLAG)
                .resourceId(limitKey)
                .actorUserIdOverride(actingUserId)
                .actorTypeOverride(actingUserId == null ? AuditActorType.SYSTEM : null)
                .organizationIdOverride(organizationId)
                .beforeState(Map.of("limitValue", String.valueOf(before)))
                .afterState(Map.of("limitValue", String.valueOf(limitValue)))
                .metadata(Map.of("source", source.name()))
                .build());
        log.info("Entitlement limit {} = {} for {} (source={}, by {})",
                limitKey, limitValue, scope, source, actingUserId);
        return saved;
    }

    @Transactional
    public FeatureFlag set(UUID organizationId, String flagKey, boolean enabled,
                           String description, UUID actingUserId) {
        return set(organizationId, flagKey, enabled, description, actingUserId, FeatureFlag.FlagSource.MANUAL);
    }

    @Transactional
    public FeatureFlag set(UUID organizationId, String flagKey, boolean enabled,
                           String description, UUID actingUserId, FeatureFlag.FlagSource source) {
        if (!enabled && PROTECTED_COMPLIANCE_FLAGS.contains(flagKey)) {
            throw new BusinessException(
                    "Feature flag '" + flagKey + "' is a protected compliance control and cannot be disabled.");
        }
        FeatureFlag flag = (organizationId == null
                ? repository.findGlobalByFlagKey(flagKey)
                : repository.findByOrganizationIdAndFlagKey(organizationId, flagKey))
                .orElseGet(() -> FeatureFlag.builder()
                        .organizationId(organizationId)
                        .flagKey(flagKey)
                        .build());
        boolean before = Boolean.TRUE.equals(flag.getEnabled());
        flag.setEnabled(enabled);
        flag.setDescription(description);
        flag.setUpdatedByUserId(actingUserId);
        flag.setSource(source);
        FeatureFlag saved = repository.save(flag);
        evictCache(organizationId, flagKey);
        if (organizationId != null) evictCache(null, flagKey);
        String scope = organizationId == null ? "GLOBAL" : organizationId.toString();
        if (actingUserId != null) {
            auditLogService.logUserAction(actingUserId, "FEATURE_FLAG_CHANGED",
                    "flag=" + flagKey + " org=" + scope + " before=" + before + " after=" + enabled + " source=" + source);
        }
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.FEATURE_FLAG_CHANGED)
                .resourceType(AuditResourceType.FEATURE_FLAG)
                .resourceId(flagKey)
                .actorUserIdOverride(actingUserId)
                .actorTypeOverride(actingUserId == null ? AuditActorType.SYSTEM : null)
                .organizationIdOverride(organizationId)
                .beforeState(Map.of("enabled", String.valueOf(before)))
                .afterState(Map.of("enabled", String.valueOf(enabled)))
                .metadata(Map.of("source", source.name()))
                .build());
        log.info("Feature flag {} = {} for {} (source={}, by {})", flagKey, enabled, scope, source, actingUserId);
        return saved;
    }

    /**
     * Re-provisions plan-derived feature flags for a subscription.
     *
     * <p>This is the only entitlement entry point, and it takes the whole
     * subscription rather than loose status/plan arguments on purpose: an
     * admin-granted comp lives on the row, and a caller passing just
     * {@code (status, plan)} would silently strip a live grant every time
     * Stripe fired a webhook.
     */
    @Transactional
    public void provisionFlagsFor(OrgSubscription sub) {
        OrgSubscription.Plan comp = sub.activeComp();
        OrgSubscription.Plan effective = comp != null
                ? comp
                : effectivePlan(sub);
        if (effective == null) return;

        for (String flagKey : PlanFeatureMatrix.ALL_GATED_FLAGS) {
            var existing = repository.findByOrganizationIdAndFlagKey(sub.getOrgId(), flagKey);
            if (existing.isPresent() && existing.get().getSource() == FeatureFlag.FlagSource.MANUAL) continue;
            boolean shouldEnable = PlanFeatureMatrix.includes(effective, flagKey);
            set(sub.getOrgId(), flagKey, shouldEnable, null, null, FeatureFlag.FlagSource.PLAN);
        }
        for (String limitKey : PlanFeatureMatrix.ALL_LIMIT_KEYS) {
            var existing = repository.findByOrganizationIdAndFlagKey(sub.getOrgId(), limitKey);
            if (existing.isPresent() && existing.get().getSource() == FeatureFlag.FlagSource.MANUAL) continue;
            Long limit = PlanFeatureMatrix.limitFor(effective, limitKey);
            setLimit(sub.getOrgId(), limitKey, limit, null, null, FeatureFlag.FlagSource.PLAN);
        }
        log.info("Provisioned feature flags for org {} (status={}, plan={}, comp={}, effective={})",
                sub.getOrgId(), sub.getStatus(), sub.getPlan(), comp, effective);
    }

    @Transactional
    public void deleteManualOverride(UUID organizationId, String flagKey) {
        (organizationId == null
                ? repository.findGlobalByFlagKey(flagKey)
                : repository.findByOrganizationIdAndFlagKey(organizationId, flagKey))
                .filter(f -> f.getSource() == FeatureFlag.FlagSource.MANUAL)
                .ifPresent(f -> {
                    repository.delete(f);
                    evictCache(organizationId, flagKey);
                    log.info("Deleted MANUAL override for flag {} on org {}", flagKey, organizationId);
                });
        if (organizationId == null) return;
        orgSubscriptionRepository.findByOrgId(organizationId).ifPresent(sub -> {
            OrgSubscription.Plan comp = sub.activeComp();
            OrgSubscription.Plan effective = comp != null
                    ? comp
                    : effectivePlan(sub);
            if (effective == null) return;
            boolean shouldEnable = PlanFeatureMatrix.includes(effective, flagKey);
            set(organizationId, flagKey, shouldEnable, null, null, FeatureFlag.FlagSource.PLAN);
        });
    }

    public List<FeatureFlag> listForOrg(UUID organizationId) {
        return repository.findByOrganizationId(organizationId);
    }

    public List<FeatureFlag> listGlobal() {
        return repository.findByOrganizationIdIsNull();
    }

    /**
     * The self-service view for an org: global flags with any org-specific override applied on
     * top, matching {@link #isEnabled}'s own resolution order (tenant row wins, global is the
     * fallback). {@link #listForOrg} alone omits every global flag, which previously left the
     * self-service endpoint unable to reflect a platform-wide flag at all.
     */
    public List<FeatureFlag> listResolvedForOrg(UUID organizationId) {
        Map<String, FeatureFlag> byKey = new LinkedHashMap<>();
        for (FeatureFlag f : listGlobal()) byKey.put(f.getFlagKey(), f);
        for (FeatureFlag f : listForOrg(organizationId)) byKey.put(f.getFlagKey(), f);
        return new ArrayList<>(byKey.values());
    }

    /**
     * SYSTEM-PLAN 28.1: TRIAL used to map straight to STARTER regardless of trialEndsAt, so an
     * org whose trial clock had run out kept STARTER-level access until some unrelated event
     * (a webhook, an admin action) happened to re-provision it -- nothing ever re-evaluated a
     * TRIAL row purely because time had passed. An expired trial now resolves the same as a
     * cancelled subscription: NONE. This makes a fresh provisioning call correct the moment it
     * runs; SYSTEM 28 TASK 28.2's scheduled job is what makes that call actually happen promptly
     * on expiry rather than waiting for the next unrelated subscription event.
     */
    private OrgSubscription.Plan effectivePlan(OrgSubscription sub) {
        OrgSubscription.Plan plan = sub.getPlan();
        return switch (sub.getStatus()) {
            case TRIAL -> (sub.getTrialEndsAt() != null && sub.getTrialEndsAt().isBefore(java.time.Instant.now()))
                    ? OrgSubscription.Plan.NONE
                    : OrgSubscription.Plan.STARTER;
            case ACTIVE              -> plan;
            case PAST_DUE            -> null;
            case CANCELLED, INACTIVE -> OrgSubscription.Plan.NONE;
        };
    }

    private boolean resolveFromDb(UUID organizationId, String flagKey, boolean fallback) {
        if (organizationId != null) {
            var tenant = repository.findByOrganizationIdAndFlagKey(organizationId, flagKey);
            if (tenant.isPresent()) return Boolean.TRUE.equals(tenant.get().getEnabled());
        }
        var global = repository.findGlobalByFlagKey(flagKey);
        if (global.isPresent()) return Boolean.TRUE.equals(global.get().getEnabled());
        return fallback;
    }

    private String cacheKey(UUID organizationId, String flagKey) {
        return KEY_PREFIX + (organizationId == null ? "GLOBAL" : organizationId) + ":" + flagKey;
    }

    private Boolean readCached(UUID organizationId, String flagKey) {
        try {
            String v = redis.opsForValue().get(cacheKey(organizationId, flagKey));
            return v == null ? null : "1".equals(v);
        } catch (Exception e) {
            log.warn("Feature-flag cache read failed for {}: {}", flagKey, e.getMessage());
            return null;
        }
    }

    private void writeCached(UUID organizationId, String flagKey, boolean enabled) {
        try {
            redis.opsForValue().set(cacheKey(organizationId, flagKey), enabled ? "1" : "0", CACHE_TTL);
        } catch (Exception e) {
            log.warn("Feature-flag cache write failed for {}: {}", flagKey, e.getMessage());
        }
    }

    private void evictCache(UUID organizationId, String flagKey) {
        stampedeGuard.invalidate(cacheKey(organizationId, flagKey));
        try {
            redis.delete(cacheKey(organizationId, flagKey));
        } catch (Exception e) {
            log.warn("Feature-flag cache evict failed for {}: {}", flagKey, e.getMessage());
        }
    }
}
