package com.recoverpro.server.config;

import com.recoverpro.server.service.safety.DataSanitizer;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SYSTEM 13 TASK 13.1.c (marked CRITICAL in the tasklist): this app handles borrower PII --
 * names, phone numbers, CKYC ids -- and an unscrubbed error tracker is a data-processor problem
 * and possibly a compliance breach. Scrub before enabling, not after.
 * <p>
 * Layered, not relying on one mechanism:
 * <ul>
 *   <li>{@code sentry.send-default-pii=false} (application.properties) -- confirmed by decompiling
 *       {@code SpringSecuritySentryUserProvider.provideUser()}: it checks this flag first and
 *       returns null before ever reading the authenticated principal, so no username/email is
 *       attached to any event at all.</li>
 *   <li>{@code sentry.max-request-body-size=none} -- the SDK never captures a request body in the
 *       first place.</li>
 *   <li>This class's {@link SentryOptions.BeforeSendCallback}, the last stage before anything
 *       leaves the process -- belt-and-suspenders for whatever the two settings above don't reach
 *       (headers, cookies, query strings, and free-text exception/event messages, which can't be
 *       blocked by a boolean flag since legitimate, safe messages live in the same field).</li>
 * </ul>
 * The exception/event-message scrub reuses {@link DataSanitizer}, the same PII-pattern matcher
 * already trusted for Lucien's LLM output safety filtering ({@code OutputSafetyFilter}) -- not a
 * new, unreviewed regex written just for this.
 */
@Configuration
@RequiredArgsConstructor
public class SentryConfig {

    private final DataSanitizer dataSanitizer;

    @Bean
    public SentryOptions.BeforeSendCallback sentryBeforeSendCallback() {
        return (event, hint) -> {
            scrubRequest(event.getRequest());
            scrubMessage(event.getMessage());
            scrubExceptionMessages(event.getExceptions());
            return event;
        };
    }

    private void scrubRequest(Request request) {
        if (request == null) return;
        // Belt-and-suspenders alongside max-request-body-size=none -- covers headers/cookies/query
        // string too, none of which that property touches, and none of which this app's error
        // tracking needs: org/user/request correlation comes from the context-tags MDC mechanism
        // (application.properties), not from raw request internals.
        request.setData(null);
        request.setHeaders(null);
        request.setCookies(null);
        request.setQueryString(null);
    }

    private void scrubMessage(Message message) {
        if (message == null) return;
        if (message.getMessage() != null) {
            message.setMessage(dataSanitizer.stripPii(message.getMessage()));
        }
        if (message.getFormatted() != null) {
            message.setFormatted(dataSanitizer.stripPii(message.getFormatted()));
        }
    }

    private void scrubExceptionMessages(java.util.List<SentryException> exceptions) {
        if (exceptions == null) return;
        for (SentryException exception : exceptions) {
            if (exception.getValue() != null) {
                exception.setValue(dataSanitizer.stripPii(exception.getValue()));
            }
        }
    }
}
