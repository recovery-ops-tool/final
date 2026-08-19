package com.recoverpro.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to start rather than silently accepting unverifiable webhook calls:
 * an enabled webhook integration with an empty signing secret would let
 * signature verification throw on every call instead of failing at boot
 * with a clear reason. Covers both payment providers this app supports —
 * SYSTEM 04 TASK 4.1 found Razorpay's webhook secret was not checked here
 * at all, only Stripe's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookSecretsStartupCheck {

    private final StripeConfig stripeConfig;
    private final RazorpayConfig razorpayConfig;

    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        if (isSet(stripeConfig.getSecretKey()) && !isSet(stripeConfig.getWebhookSecret())) {
            throw new IllegalStateException(
                    "Stripe is enabled (app.stripe.secret-key is set) but app.stripe.webhook-secret is empty. "
                            + "Refusing to start: signature verification cannot fail closed with no secret.");
        }
        if (isSet(razorpayConfig.getKeyId()) && isSet(razorpayConfig.getKeySecret())
                && !isSet(razorpayConfig.getWebhookSecret())) {
            throw new IllegalStateException(
                    "Razorpay is enabled (app.razorpay.key-id/key-secret are set) but "
                            + "app.razorpay.webhook-secret is empty. Refusing to start: signature verification "
                            + "cannot fail closed with no secret.");
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
