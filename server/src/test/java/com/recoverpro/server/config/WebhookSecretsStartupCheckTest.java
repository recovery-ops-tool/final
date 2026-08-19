package com.recoverpro.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM 04 TASK 4.1 gap fix: {@link WebhookSecretsStartupCheck} previously only checked
 * Stripe's webhook secret, leaving an active Razorpay integration (key-id/key-secret set) with
 * no equivalent check. These tests cover both providers, plus the case that mattered for the
 * fix: Razorpay only becomes "active" (and its webhook secret only becomes required) once BOTH
 * key-id AND key-secret are set — mirroring {@link RazorpayConfig#init}'s own condition exactly.
 */
class WebhookSecretsStartupCheckTest {

    @Test
    void neitherProviderConfigured_startsCleanly() {
        WebhookSecretsStartupCheck check = new WebhookSecretsStartupCheck(new StripeConfig(), new RazorpayConfig());

        assertThatCode(check::verify).doesNotThrowAnyException();
    }

    @Test
    void stripeSecretKeySetWithoutWebhookSecret_refusesToStart() {
        StripeConfig stripeConfig = new StripeConfig();
        ReflectionTestUtils.setField(stripeConfig, "secretKey", "sk_test_123");
        ReflectionTestUtils.setField(stripeConfig, "webhookSecret", "");
        WebhookSecretsStartupCheck check = new WebhookSecretsStartupCheck(stripeConfig, new RazorpayConfig());

        assertThatThrownBy(check::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stripe");
    }

    @Test
    void stripeFullyConfigured_startsCleanly() {
        StripeConfig stripeConfig = new StripeConfig();
        ReflectionTestUtils.setField(stripeConfig, "secretKey", "sk_test_123");
        ReflectionTestUtils.setField(stripeConfig, "webhookSecret", "whsec_123");
        WebhookSecretsStartupCheck check = new WebhookSecretsStartupCheck(stripeConfig, new RazorpayConfig());

        assertThatCode(check::verify).doesNotThrowAnyException();
    }

    @Test
    void razorpayKeysSetWithoutWebhookSecret_refusesToStart() {
        RazorpayConfig razorpayConfig = new RazorpayConfig();
        ReflectionTestUtils.setField(razorpayConfig, "keyId", "rzp_test_123");
        ReflectionTestUtils.setField(razorpayConfig, "keySecret", "secret123");
        ReflectionTestUtils.setField(razorpayConfig, "webhookSecret", "");
        WebhookSecretsStartupCheck check = new WebhookSecretsStartupCheck(new StripeConfig(), razorpayConfig);

        assertThatThrownBy(check::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Razorpay");
    }

    @Test
    void razorpayFullyConfigured_startsCleanly() {
        RazorpayConfig razorpayConfig = new RazorpayConfig();
        ReflectionTestUtils.setField(razorpayConfig, "keyId", "rzp_test_123");
        ReflectionTestUtils.setField(razorpayConfig, "keySecret", "secret123");
        ReflectionTestUtils.setField(razorpayConfig, "webhookSecret", "whsec_123");
        WebhookSecretsStartupCheck check = new WebhookSecretsStartupCheck(new StripeConfig(), razorpayConfig);

        assertThatCode(check::verify).doesNotThrowAnyException();
    }

    @Test
    void razorpayOnlyKeyIdSet_notYetActive_doesNotRequireWebhookSecret() {
        RazorpayConfig razorpayConfig = new RazorpayConfig();
        ReflectionTestUtils.setField(razorpayConfig, "keyId", "rzp_test_123");
        ReflectionTestUtils.setField(razorpayConfig, "keySecret", "");
        ReflectionTestUtils.setField(razorpayConfig, "webhookSecret", "");
        WebhookSecretsStartupCheck check = new WebhookSecretsStartupCheck(new StripeConfig(), razorpayConfig);

        assertThatCode(check::verify).doesNotThrowAnyException();
    }
}
