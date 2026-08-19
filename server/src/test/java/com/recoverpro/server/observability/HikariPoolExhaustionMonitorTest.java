package com.recoverpro.server.observability;

import com.recoverpro.server.service.OpsAlertService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HikariPoolExhaustionMonitorTest {

    @Mock private MeterRegistry meterRegistry;
    @Mock private Search search;
    @Mock private Gauge gauge;
    @Mock private OpsAlertService opsAlertService;

    private HikariPoolExhaustionMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new HikariPoolExhaustionMonitor(meterRegistry, opsAlertService);
        when(meterRegistry.find("hikaricp.connections.pending")).thenReturn(search);
        when(search.gauge()).thenReturn(gauge);
    }

    @Test
    void singleBlip_doesNotAlert() {
        when(gauge.value()).thenReturn(1.0);
        monitor.checkPendingConnections();

        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }

    @Test
    void sustainedExhaustion_alertsAfterFourConsecutiveChecks() {
        when(gauge.value()).thenReturn(2.0);

        monitor.checkPendingConnections(); // 1
        monitor.checkPendingConnections(); // 2
        monitor.checkPendingConnections(); // 3
        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());

        monitor.checkPendingConnections(); // 4 -- sustained threshold
        verify(opsAlertService, times(1))
                .alertJobFailure(eq("HikariConnectionPoolExhaustion"), any(), isNull());
    }

    @Test
    void recoveryResetsTheStreak() {
        when(gauge.value()).thenReturn(1.0);
        monitor.checkPendingConnections();
        monitor.checkPendingConnections();
        monitor.checkPendingConnections();

        when(gauge.value()).thenReturn(0.0);
        monitor.checkPendingConnections(); // recovers, streak resets

        when(gauge.value()).thenReturn(1.0);
        monitor.checkPendingConnections();
        monitor.checkPendingConnections();
        monitor.checkPendingConnections();
        // only 3 consecutive since the reset -- still below the 4-check threshold
        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }

    @Test
    void missingGauge_doesNotThrow() {
        when(search.gauge()).thenReturn(null);
        monitor.checkPendingConnections();
        monitor.checkPendingConnections();

        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }
}
