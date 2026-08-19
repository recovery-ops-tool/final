package com.recoverpro.server.observability;

import com.recoverpro.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 12 TASK 12.1: proves the literal acceptance criterion against a real running instance
 * (not just reading {@code application.properties} and trusting it), since this codebase has
 * already been burned once by config that looked right on paper (SYSTEM 05's Flyway readiness
 * group) but didn't actually boot.
 */
class MetricsExportTest extends AbstractIntegrationTest {

    @Test
    void prometheusEndpoint_isPublicAndExportsRequiredMetrics() {
        // Generates at least one http.server.requests sample -- Micrometer's Timer only appears
        // in a scrape after its first recorded call, so scraping cold (with zero prior requests)
        // would produce a false negative on http_server_requests_seconds even though the mechanism
        // works correctly once real traffic exists.
        restTemplate.getForEntity(baseUrl("/actuator/health"), String.class);

        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode().value())
                .as("must be reachable with no JWT -- an external Prometheus scraper cannot authenticate")
                .isEqualTo(200);
        String body = response.getBody();
        assertThat(body).contains("http_server_requests_seconds");
        assertThat(body).contains("resilience4j_circuitbreaker_state");
        assertThat(body).contains("hikaricp_connections_pending");
    }
}
