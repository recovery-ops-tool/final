package com.recoverpro.server.observability;

import com.recoverpro.server.service.OpsAlertService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SYSTEM 12 TASK 12.3.a: "any circuit breaker OPEN" is one of the alert categories the task lists
 * by name, and nothing paged anyone on it before this -- {@code resilience4j_circuitbreaker_state}
 * was exposed on {@code /actuator/prometheus} (confirmed in {@code MetricsExportTest}), but reading
 * that requires an external Prometheus/Alertmanager stack that doesn't exist yet (no such service
 * appears anywhere in {@code docker-compose.yml}, and adding one is a real resource-footprint
 * decision on the single free-tier OCI box this app targets -- see {@code docs/INFRA-CURRENT.md} --
 * not something to silently commit to as a side effect of this task).
 * <p>
 * Same reasoning and pattern as {@link HikariPoolExhaustionMonitor}: reuse the existing
 * {@link OpsAlertService} email/notification path in-process instead, at effectively zero added
 * resource cost. Unlike the Hikari monitor this is event-driven, not polled -- Resilience4j already
 * pushes a state-transition event the instant it happens, so there's nothing to poll.
 * <p>
 * Only CLOSED/HALF_OPEN -&gt; OPEN transitions alert -- those are the library's own automatic
 * failure-rate/slow-call detection kicking in, i.e. a real degradation just started.
 * FORCED_OPEN/DISABLED/METRICS_ONLY transitions are deliberate operator actions (nobody configures
 * these via this app's properties today, but the annotation-driven {@code llamaEmbedding} instance
 * could be forced into one via Resilience4j's admin API in the future) and don't warrant paging
 * someone about their own action. Recovery (-&gt; CLOSED) is logged, not alerted -- it isn't a
 * failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerAlertListener {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OpsAlertService opsAlertService;

    @PostConstruct
    void subscribe() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::attach);
        // Config-named instances (tts/stt/llama) are created eagerly at startup and caught by the
        // loop above; the annotation-only llamaEmbedding instance is created lazily on its first
        // call, so it doesn't exist yet at @PostConstruct time -- this catches it (and any other
        // future instance) the moment the registry creates it.
        circuitBreakerRegistry.getEventPublisher().onEntryAdded(event -> attach(event.getAddedEntry()));
    }

    private void attach(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State from = event.getStateTransition().getFromState();
            CircuitBreaker.State to = event.getStateTransition().getToState();
            if (to == CircuitBreaker.State.OPEN) {
                log.warn("Circuit breaker '{}' opened: {} -> OPEN", circuitBreaker.getName(), from);
                opsAlertService.alertJobFailure(
                        "CircuitBreakerOpen[" + circuitBreaker.getName() + "]",
                        "transitioned " + from + " -> OPEN -- calls are now short-circuited to the fallback",
                        null);
            } else if (to == CircuitBreaker.State.CLOSED && from != CircuitBreaker.State.CLOSED) {
                log.info("Circuit breaker '{}' recovered: {} -> CLOSED", circuitBreaker.getName(), from);
            }
        });
    }
}
