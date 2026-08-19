package com.recoverpro.server.common.exception;

import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 13 TASK 13.1/13.2. Two things pinned down together since they're the same class:
 * 13.1's "a deliberately thrown test exception appears in the tracker" (the capture call itself,
 * since no real Sentry account exists to verify delivery against -- see
 * docs/SYSTEM-13-ERROR-TRACKING.md) and 13.2's "an unhandled exception returns a clean ApiResponse
 * error with no stack trace in the body."
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/allocations");

    @Test
    void handleGeneric_capturesToSentry() {
        RuntimeException ex = new RuntimeException("boom");
        try (MockedStatic<Sentry> sentry = Mockito.mockStatic(Sentry.class)) {
            handler.handleGeneric(ex, request);

            sentry.verify(() -> Sentry.captureException(ex));
        }
    }

    @Test
    void handleGeneric_responseBodyLeaksNoStackTraceOrExceptionDetails() {
        RuntimeException ex = new RuntimeException("internal detail: connection string postgres://user:pass@host/db");
        try (MockedStatic<Sentry> ignored = Mockito.mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage())
                    .as("the generic handler must never echo the real exception message back to the client")
                    .isEqualTo("An unexpected error occurred")
                    .doesNotContain("connection string", "postgres://");
            assertThat(response.getBody().getError()).doesNotContain("RuntimeException");
        }
    }

    @Test
    void handleBusinessException_doesNotCaptureToSentry() {
        // Curated, expected exceptions (client errors) are not "the new exception type appearing
        // after a deploy" TASK 13.1 exists to catch -- only handleGeneric's catch-all is wired to
        // Sentry, deliberately (see GlobalExceptionHandler.handleGeneric's own comment).
        BusinessException ex = new BusinessException("Cannot manage feature flags for another organization");
        try (MockedStatic<Sentry> sentry = Mockito.mockStatic(Sentry.class)) {
            handler.handleBusiness(ex, request);

            sentry.verifyNoInteractions();
        }
    }
}
