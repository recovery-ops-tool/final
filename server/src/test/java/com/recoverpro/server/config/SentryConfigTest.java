package com.recoverpro.server.config;

import com.recoverpro.server.service.safety.DataSanitizer;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 13 TASK 13.1.c (CRITICAL): this app handles borrower PII, so every field an error-tracker
 * event could carry PII in is exercised here directly, against the real {@link DataSanitizer} (not
 * a mock) -- the actual regexes that decide what counts as PII are exactly what needs proving, not
 * just that some scrubbing function gets called.
 */
class SentryConfigTest {

    private final SentryConfig sentryConfig = new SentryConfig(new DataSanitizer());
    private final io.sentry.SentryOptions.BeforeSendCallback callback = sentryConfig.sentryBeforeSendCallback();

    @Test
    void requestBodyHeadersCookiesQueryString_allStripped() {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setData("{\"phone\":\"9876543210\"}");
        request.setHeaders(java.util.Map.of("Authorization", "Bearer secret-token"));
        request.setCookies("session=abc123");
        request.setQueryString("email=borrower@example.com");
        event.setRequest(request);

        SentryEvent result = callback.execute(event, new Hint());

        assertThat(result.getRequest().getData()).isNull();
        assertThat(result.getRequest().getHeaders()).isNull();
        assertThat(result.getRequest().getCookies()).isNull();
        assertThat(result.getRequest().getQueryString()).isNull();
    }

    @Test
    void exceptionMessageContainingPhoneNumber_redacted() {
        SentryEvent event = new SentryEvent();
        SentryException exception = new SentryException();
        exception.setValue("Borrower lookup failed for phone 9876543210");
        event.setExceptions(List.of(exception));

        SentryEvent result = callback.execute(event, new Hint());

        assertThat(result.getExceptions().get(0).getValue())
                .doesNotContain("9876543210")
                .contains("[REDACTED]");
    }

    @Test
    void exceptionMessageContainingEmail_redacted() {
        SentryEvent event = new SentryEvent();
        SentryException exception = new SentryException();
        exception.setValue("Duplicate email: jane.doe@example.com");
        event.setExceptions(List.of(exception));

        SentryEvent result = callback.execute(event, new Hint());

        assertThat(result.getExceptions().get(0).getValue())
                .doesNotContain("jane.doe@example.com")
                .contains("[REDACTED]");
    }

    @Test
    void eventMessageContainingPan_redacted() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("PAN ABCDE1234F already linked to another borrower");
        event.setMessage(message);

        SentryEvent result = callback.execute(event, new Hint());

        assertThat(result.getMessage().getMessage())
                .doesNotContain("ABCDE1234F")
                .contains("[REDACTED]");
    }

    @Test
    void noRequestOrExceptionsOrMessage_doesNotThrow() {
        SentryEvent event = new SentryEvent();

        SentryEvent result = callback.execute(event, new Hint());

        assertThat(result).isSameAs(event);
    }

    @Test
    void ordinaryExceptionMessageWithNoPii_passesThroughUnchanged() {
        SentryEvent event = new SentryEvent();
        SentryException exception = new SentryException();
        exception.setValue("Connection refused");
        event.setExceptions(List.of(exception));

        SentryEvent result = callback.execute(event, new Hint());

        assertThat(result.getExceptions().get(0).getValue()).isEqualTo("Connection refused");
    }
}
