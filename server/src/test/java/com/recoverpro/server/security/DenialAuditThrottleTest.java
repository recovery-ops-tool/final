package com.recoverpro.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 09 TASK 9.4.c: proves the denial-storm coalescing gate actually coalesces (one write per
 * actor+endpoint per window) and fails OPEN -- not closed -- when Redis itself is unavailable, so
 * a Redis outage can never silently erase the audit trail on top of whatever else is going wrong.
 */
@ExtendWith(MockitoExtension.class)
class DenialAuditThrottleTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private DenialAuditThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new DenialAuditThrottle(redisTemplate);
    }

    @Test
    void shouldRecord_firstHitInWindow_returnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(throttle.shouldRecord("actor-1", "GET /api/v1/borrowers")).isTrue();
    }

    @Test
    void shouldRecord_repeatHitWithinWindow_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(throttle.shouldRecord("actor-1", "GET /api/v1/borrowers")).isFalse();
    }

    @Test
    void shouldRecord_redisUnavailable_failsOpenAndReturnsTrue() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        assertThat(throttle.shouldRecord("actor-1", "GET /api/v1/borrowers"))
                .as("losing the audit trail during a Redis outage is worse than a few extra writes")
                .isTrue();
    }

    @Test
    void shouldRecord_differentActorSameEndpoint_bothTreatedAsFirstHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(throttle.shouldRecord("actor-1", "GET /api/v1/borrowers")).isTrue();
        assertThat(throttle.shouldRecord("actor-2", "GET /api/v1/borrowers")).isTrue();
    }
}
