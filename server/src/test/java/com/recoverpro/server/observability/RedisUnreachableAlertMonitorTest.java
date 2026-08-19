package com.recoverpro.server.observability;

import com.recoverpro.server.service.OpsAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisUnreachableAlertMonitorTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisConnectionFactory connectionFactory;
    @Mock private RedisConnection connection;
    @Mock private OpsAlertService opsAlertService;

    private RedisUnreachableAlertMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new RedisUnreachableAlertMonitor(redisTemplate, opsAlertService);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
    }

    @Test
    void reachable_neverAlerts() {
        when(connection.ping()).thenReturn("PONG");

        monitor.checkReachability();
        monitor.checkReachability();

        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }

    @Test
    void singleBlip_doesNotAlert() {
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"));

        monitor.checkReachability();
        monitor.checkReachability();

        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }

    @Test
    void sustainedUnreachability_alertsAfterThreeConsecutiveChecks() {
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"));

        monitor.checkReachability(); // 1
        monitor.checkReachability(); // 2
        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());

        monitor.checkReachability(); // 3 -- sustained threshold
        verify(opsAlertService, times(1)).alertJobFailure(eq("RedisUnreachable"), any(), any());
    }

    @Test
    void recoveryResetsTheStreak() {
        // doThrow/doReturn, not when().thenX() -- re-stubbing a method with when() after it was
        // previously stubbed to throw re-invokes the real (throwing) stub as part of when()'s own
        // recording step, so the exception would escape here instead of inside checkReachability().
        doThrow(new RuntimeException("connection refused")).when(connection).ping();
        monitor.checkReachability();
        monitor.checkReachability();

        doReturn("PONG").when(connection).ping();
        monitor.checkReachability(); // recovers, streak resets

        doThrow(new RuntimeException("connection refused")).when(connection).ping();
        monitor.checkReachability();
        monitor.checkReachability();
        // only 2 consecutive since the reset -- still below the 3-check threshold
        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }
}
