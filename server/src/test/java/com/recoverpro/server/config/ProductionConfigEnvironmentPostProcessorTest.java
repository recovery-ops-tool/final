package com.recoverpro.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM 04 TASK 4.2 — unit tests for {@link ProductionConfigEnvironmentPostProcessor}. No
 * Spring context needed: the class under test reads directly off a
 * {@link org.springframework.core.env.ConfigurableEnvironment}, which {@link MockEnvironment}
 * (a real implementation, not a mock of this class's own behavior) provides standalone.
 */
class ProductionConfigEnvironmentPostProcessorTest {

    private final ProductionConfigEnvironmentPostProcessor processor =
            new ProductionConfigEnvironmentPostProcessor();

    @Test
    void nonProdProfile_neverValidates_evenWithEverythingMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        assertThatCode(() -> processor.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_everythingConfigured_startsCleanly() {
        MockEnvironment env = fullyConfiguredProdEnvironment();

        assertThatCode(() -> processor.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_blankDbPassword_refusesToStart() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("DB_PASSWORD", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("Refusing to start");
    }

    @Test
    void prodProfile_blankJwtSecret_refusesToStart() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("JWT_SECRET", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void prodProfile_blankTrustedProxyCidr_refusesToStart() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("TRUSTED_PROXY_CIDR", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRUSTED_PROXY_CIDR");
    }

    @Test
    void prodProfile_localProviderBlankEncryptionKey_refusesToStart() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("PII_ENCRYPTION_KEY_BASE64", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII_ENCRYPTION_KEY_BASE64");
    }

    @Test
    void prodProfile_encryptionDisabled_doesNotRequireKey() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("PII_ENCRYPTION_ENABLED", "false");
        env.setProperty("PII_ENCRYPTION_KEY_BASE64", "");

        assertThatCode(() -> processor.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_kmsProvider_requiresKmsKeyIdNotLocalKey() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("PII_ENCRYPTION_PROVIDER", "kms");
        env.setProperty("PII_ENCRYPTION_KEY_BASE64", "");
        env.setProperty("PII_ENCRYPTION_KMS_KEY_ID", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII_ENCRYPTION_KMS_KEY_ID")
                .hasMessageNotContaining("PII_ENCRYPTION_KEY_BASE64");
    }

    @Test
    void prodProfile_multipleMissingVars_reportsAllInOneException() {
        MockEnvironment env = fullyConfiguredProdEnvironment();
        env.setProperty("DB_PASSWORD", "");
        env.setProperty("JWT_SECRET", "");
        env.setProperty("TRUSTED_PROXY_CIDR", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("TRUSTED_PROXY_CIDR")
                .hasMessageContaining("3 problem(s)");
    }

    private static MockEnvironment fullyConfiguredProdEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("DB_PASSWORD", "a-real-password");
        env.setProperty("JWT_SECRET", "a-real-secret-that-is-at-least-256-bits-long-ok");
        // SYSTEM 07 TASK 7.1: despite the var's name, Tomcat's RemoteIpValve compiles this value
        // as a regex (Pattern.compile), not CIDR notation -- using a realistic-looking regex
        // here rather than a CIDR string, so this fixture doesn't model the wrong format.
        env.setProperty("TRUSTED_PROXY_CIDR", "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
        env.setProperty("PII_ENCRYPTION_ENABLED", "true");
        env.setProperty("PII_ENCRYPTION_PROVIDER", "local");
        env.setProperty("PII_ENCRYPTION_KEY_BASE64", "cmVhbC1rZXktbWF0ZXJpYWwtbm90LXJlYWxseQ==");
        return env;
    }
}
