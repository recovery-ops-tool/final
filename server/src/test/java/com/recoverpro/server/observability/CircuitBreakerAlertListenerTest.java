package com.recoverpro.server.observability;

import com.recoverpro.server.service.OpsAlertService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Uses a real {@link CircuitBreakerRegistry}/{@link CircuitBreaker} (not mocked) and drives real
 * state transitions -- this is the library's own event-publishing machinery under test, not a
 * hand-rolled substitute for it, so mocking it would just prove the mock does what it's told.
 */
@ExtendWith(MockitoExtension.class)
class CircuitBreakerAlertListenerTest {

    @Mock private OpsAlertService opsAlertService;

    @Test
    void breakerOpensOnAPreExistingInstance_alerts() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker circuitBreaker = registry.circuitBreaker("tts"); // exists before subscribe()

        new CircuitBreakerAlertListener(registry, opsAlertService).subscribe();
        circuitBreaker.transitionToOpenState();

        verify(opsAlertService).alertJobFailure(eq("CircuitBreakerOpen[tts]"), contains("OPEN"), any());
    }

    @Test
    void breakerCreatedAfterSubscribe_stillAlertsOnOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        new CircuitBreakerAlertListener(registry, opsAlertService).subscribe();

        // Created after subscribe() -- proves the onEntryAdded path (not just the startup loop
        // over getAllCircuitBreakers()) is what catches the annotation-only llamaEmbedding-style
        // instance that doesn't exist yet at @PostConstruct time.
        CircuitBreaker circuitBreaker = registry.circuitBreaker("llamaEmbedding");
        circuitBreaker.transitionToOpenState();

        verify(opsAlertService).alertJobFailure(eq("CircuitBreakerOpen[llamaEmbedding]"), any(), any());
    }

    @Test
    void recoveryToClosed_doesNotAlert() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker circuitBreaker = registry.circuitBreaker("stt");
        new CircuitBreakerAlertListener(registry, opsAlertService).subscribe();

        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToClosedState();

        verify(opsAlertService).alertJobFailure(any(), any(), any()); // exactly the OPEN transition
    }

    @Test
    void forcedOpen_doesNotAlert() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker circuitBreaker = registry.circuitBreaker("llama");
        new CircuitBreakerAlertListener(registry, opsAlertService).subscribe();

        circuitBreaker.transitionToForcedOpenState();

        verify(opsAlertService, never()).alertJobFailure(any(), any(), any());
    }
}
