package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 20.4: before this task, {@code monthlyLimit} was a single flat
 * {@code @Value}-injected number applied identically to every org regardless of plan -- not
 * actually "capped per plan" as TASK 20.4's acceptance wording requires. Now the cap is
 * {@link PlanFeatureMatrix#LUCIEN_MONTHLY_TOKEN_LIMIT}, resolved per-org through
 * {@link EntitlementService}, same as every other plan-derived limit. These tests replace the
 * (nonexistent, this class had no prior test) coverage for {@code checkBudget}/{@code recordUsage}.
 */
@ExtendWith(MockitoExtension.class)
class LucienTokenBudgetServiceImplTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private UserRepository userRepository;
    @Mock private EntitlementService entitlementService;
    @Mock private ValueOperations<String, String> valueOps;

    private LucienTokenBudgetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LucienTokenBudgetServiceImpl(redisTemplate, userRepository, entitlementService);
        ReflectionTestUtils.setField(service, "enabled", true);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void checkBudget_planResolvesUnlimited_neverReadsRedis() {
        UUID orgId = UUID.randomUUID();
        when(entitlementService.getLimit(orgId, PlanFeatureMatrix.LUCIEN_MONTHLY_TOKEN_LIMIT))
                .thenReturn(Optional.empty());

        service.checkBudget(orgId);

        verify(valueOps, never()).get(anyString());
    }

    @Test
    void checkBudget_belowCap_allows() {
        UUID orgId = UUID.randomUUID();
        when(entitlementService.getLimit(orgId, PlanFeatureMatrix.LUCIEN_MONTHLY_TOKEN_LIMIT))
                .thenReturn(Optional.of(1_000_000L));
        when(valueOps.get(any())).thenReturn("999999");

        service.checkBudget(orgId); // must not throw
    }

    @Test
    void checkBudget_atOrAboveCap_throwsWithActionableMessage() {
        UUID orgId = UUID.randomUUID();
        when(entitlementService.getLimit(orgId, PlanFeatureMatrix.LUCIEN_MONTHLY_TOKEN_LIMIT))
                .thenReturn(Optional.of(1_000_000L));
        when(valueOps.get(any())).thenReturn("1000000");

        assertThatThrownBy(() -> service.checkBudget(orgId))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Upgrade your plan");
    }

    @Test
    void checkBudget_disabledKillSwitch_skipsEnforcementEvenAtCap() {
        ReflectionTestUtils.setField(service, "enabled", false);
        UUID orgId = UUID.randomUUID();

        service.checkBudget(orgId); // must not throw, must not even consult EntitlementService

        verify(entitlementService, never()).getLimit(any(), any());
    }

    @Test
    void recordUsage_alwaysTracked_evenWhenPlanIsUnlimited() {
        UUID orgId = UUID.randomUUID();

        service.recordUsage(orgId, 100, 200);

        verify(valueOps).increment(anyString(), org.mockito.ArgumentMatchers.eq(300L));
    }

    @Test
    void recordUsage_disabledKillSwitch_skipsTracking() {
        ReflectionTestUtils.setField(service, "enabled", false);
        UUID orgId = UUID.randomUUID();

        service.recordUsage(orgId, 100, 200);

        verify(valueOps, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
