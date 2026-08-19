package com.recoverpro.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 04 TASK 4.3 — {@link AwsSecretsManagerEnvironmentPostProcessor}. The
 * {@code postProcessEnvironment(environment, application)} entry point is only exercised for the
 * disabled path (real behavior, no AWS involved — the actually important safety property: an
 * unconfigured deployment is completely unaffected). The fetch/fallback/error logic
 * ({@link AwsSecretsManagerEnvironmentPostProcessor#apply}) is tested directly against a mocked
 * {@link SecretsManagerClient} — there is no local AWS Secrets Manager emulator in this repo, and
 * no live secret-manager instance is provisioned yet (see docs/CONFIG-REFERENCE.md), so this is
 * the honest boundary of what can be verified without real AWS infrastructure.
 */
class AwsSecretsManagerEnvironmentPostProcessorTest {

    private final AwsSecretsManagerEnvironmentPostProcessor processor =
            new AwsSecretsManagerEnvironmentPostProcessor();

    @Test
    void disabled_isANoOp_doesNotTouchEnvironment() {
        MockEnvironment env = new MockEnvironment();
        // No AWS_SECRETS_MANAGER_ENABLED set at all -- the real-world default.

        assertThatCode(() -> processor.postProcessEnvironment(env, null)).doesNotThrowAnyException();
        assertThat(env.getPropertySources().contains("aws-secrets-manager")).isFalse();
    }

    @Test
    void runsBeforeProductionConfigCheck() {
        assertThat(processor.getOrder())
                .isLessThan(new ProductionConfigEnvironmentPostProcessor().getOrder());
    }

    @Test
    void secretFound_injectedIntoEnvironment_visibleUnderItsVarName() {
        MockEnvironment env = new MockEnvironment();
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        // Every other tracked variable "not found" -- realistic partial-migration state.
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());
        when(client.getSecretValue(matchingSecretId("recoverpro/JWT_SECRET")))
                .thenReturn(GetSecretValueResponse.builder().secretString("fetched-jwt-secret").build());

        processor.apply(env, client, "ap-south-1");

        assertThat(env.getProperty("JWT_SECRET")).isEqualTo("fetched-jwt-secret");
    }

    @Test
    void secretNotMigratedYet_fallsThroughWithoutError() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DB_PASSWORD", "existing-env-value");
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        assertThatCode(() -> processor.apply(env, client, "ap-south-1")).doesNotThrowAnyException();
        assertThat(env.getProperty("DB_PASSWORD")).isEqualTo("existing-env-value");
    }

    @Test
    void secretsManagerUnreachable_refusesToStart() {
        MockEnvironment env = new MockEnvironment();
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertThatThrownBy(() -> processor.apply(env, client, "ap-south-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_SECRETS_MANAGER_ENABLED");
    }

    @Test
    void secretsManagerAuthFailure_refusesToStart() {
        MockEnvironment env = new MockEnvironment();
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow((SecretsManagerException) SecretsManagerException.builder()
                        .message("access denied").build());

        assertThatThrownBy(() -> processor.apply(env, client, "ap-south-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customPrefix_isRespected() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("AWS_SECRETS_MANAGER_PREFIX", "myorg/staging/");
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        // Stub the catch-all first -- Mockito prefers the most specific matching stub, but only
        // among stubs already registered, so the specific one below must come last to win.
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());
        when(client.getSecretValue(matchingSecretId("myorg/staging/JWT_SECRET")))
                .thenReturn(GetSecretValueResponse.builder().secretString("staging-secret").build());

        processor.apply(env, client, "ap-south-1");

        assertThat(env.getProperty("JWT_SECRET")).isEqualTo("staging-secret");
    }

    private static GetSecretValueRequest matchingSecretId(String secretId) {
        return eq(GetSecretValueRequest.builder().secretId(secretId).build());
    }
}
