package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.LucienTokenBudgetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * Backed by Redis; keys expire automatically at end of billing period. The cap itself
 * (TASK 20.4) is no longer a single flat number applied to every org regardless of plan --
 * it's {@link PlanFeatureMatrix#LUCIEN_MONTHLY_TOKEN_LIMIT}, resolved per-org through
 * {@link EntitlementService}, same as every other plan-derived limit (see
 * docs/SYSTEM-20-ENTITLEMENT.md). {@code lucien.token-budget.enabled=false} is a separate ops
 * kill-switch (e.g. incident response) that bypasses enforcement entirely regardless of plan --
 * distinct from a plan resolving to "unlimited" (Enterprise), which still records usage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LucienTokenBudgetServiceImpl implements LucienTokenBudgetService {

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final EntitlementService entitlementService;

    @Value("${lucien.token-budget.enabled:true}")
    private boolean enabled;

    private static final String KEY_PREFIX = "lucien:tokens:";
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    @Override
    public Optional<UUID> resolveOrgId(UUID agentId) {
        return userRepository.findById(agentId).map(u -> u.getOrganizationId());
    }

    @Override
    public void checkBudget(UUID orgId) {
        if (!enabled || orgId == null) return;
        Optional<Long> limit = entitlementService.getLimit(orgId, PlanFeatureMatrix.LUCIEN_MONTHLY_TOKEN_LIMIT);
        if (limit.isEmpty()) return; // unlimited under this org's plan
        long cap = limit.get();
        String key = redisKey(orgId);
        try {
            String raw = redisTemplate.opsForValue().get(key);
            long used = raw != null ? Long.parseLong(raw) : 0L;
            if (used >= cap) {
                log.warn("LucienTokenBudget: org {} exhausted monthly budget ({}/{})", orgId, used, cap);
                throw new RateLimitExceededException(
                        String.format("Monthly AI usage limit reached for your organisation's plan (%,d of %,d tokens used). Upgrade your plan or wait until it resets on the 1st.", used, cap),
                        secondsUntilNextMonth());
            }
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("LucienTokenBudget Redis error (check): {}", e.getMessage());
        }
    }

    @Override
    public void recordUsage(UUID orgId, int inputTokens, int outputTokens) {
        if (!enabled || orgId == null) return;
        int total = (inputTokens > 0 ? inputTokens : 0) + (outputTokens > 0 ? outputTokens : 0);
        if (total <= 0) return;
        String key = redisKey(orgId);
        try {
            Long count = redisTemplate.opsForValue().increment(key, total);
            if (count != null && count <= total) {
                redisTemplate.expire(key, Duration.ofSeconds(secondsUntilNextMonth() + 86_400));
            }
            log.debug("LucienTokenBudget: org {} used {} tokens this month", orgId, count);
        } catch (Exception e) {
            log.error("LucienTokenBudget Redis error (record): {}", e.getMessage());
        }
    }

    private String redisKey(UUID orgId) {
        YearMonth ym = YearMonth.now();
        return KEY_PREFIX + orgId + ":" + ym.getYear() + "-" + String.format("%02d", ym.getMonthValue());
    }

    private long secondsUntilNextMonth() {
        YearMonth next = YearMonth.now();
        java.time.Instant nextMonthStart = next.plusMonths(1).atDay(1).atStartOfDay(IST).toInstant();
        return java.time.Duration.between(java.time.Instant.now(), nextMonthStart).getSeconds();
    }
}
