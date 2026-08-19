package com.recoverpro.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SYSTEM 04 TASK 4.3 — optional secrets-manager sourcing for the variables in
 * {@link #SECRET_VARIABLE_NAMES}, which {@code docs/CONFIG-REFERENCE.md} identifies as genuine
 * secret material (as opposed to config that happens to be sensitive-ish, like a GSTIN, or a
 * public key like {@code STRIPE_PUBLISHABLE_KEY}).
 * <p>
 * Disabled by default ({@code AWS_SECRETS_MANAGER_ENABLED} unset/false) — env vars (optionally via
 * {@code .env}, see {@code spring-dotenv}) remain the default path, so local development and any
 * existing deployment are completely unaffected. TASK 4.3.b's explicit requirement.
 * <p>
 * When enabled, each secret is looked up in AWS Secrets Manager under
 * {@code <AWS_SECRETS_MANAGER_PREFIX><VAR_NAME>} (default prefix {@code recoverpro/}) and, if
 * found, injected into the {@link ConfigurableEnvironment} as the highest-priority property
 * source — so every existing {@code ${VAR_NAME:default}} placeholder in
 * {@code application*.properties} resolves to it transparently, with no change needed anywhere
 * else. A secret not yet migrated into Secrets Manager (not found) is skipped, not failed —
 * migration can happen one variable at a time, which is the whole point of keeping env vars as a
 * live fallback rather than an all-or-nothing switch. A genuine failure to reach Secrets Manager
 * at all (bad credentials, network partition, wrong region) is NOT swallowed the same way: if an
 * operator explicitly turned this mode on, a client that can't talk to AWS at all is exactly the
 * kind of "half-configured deploy" TASK 4.2 exists to refuse rather than silently degrade.
 * <p>
 * Credentials: deliberately uses the AWS SDK's default credential provider chain (same pattern as
 * {@link com.recoverpro.server.security.encryption.KmsEnvelopeEncryptor}, not
 * {@link S3Config}'s explicit static-credentials pattern) so this also works via an IAM role if
 * compute ever moves onto AWS, not only via {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY}.
 * On the OCI deployment target this app actually has today (see {@code docs/INFRA-CURRENT.md}),
 * no IAM role is available, so those two env vars remain a required bootstrap credential even
 * with this mode on — see {@code docs/RUNBOOK-SECRETS.md} for why that's an inherent limit of
 * reaching an AWS-hosted secret store from non-AWS compute, not an oversight.
 * <p>
 * Ordered ahead of {@link ProductionConfigEnvironmentPostProcessor} so that TASK 4.2's fail-fast
 * check sees secrets sourced from here as present, not missing.
 */
@Slf4j
public class AwsSecretsManagerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "aws-secrets-manager";
    private static final String DEFAULT_PREFIX = "recoverpro/";

    /**
     * Genuine secret material only — excludes identifiers that are sensitive-ish but not secrets
     * (e.g. {@code PII_ENCRYPTION_KMS_KEY_ID}, a KMS key ARN, not key material) and anything
     * required just to bootstrap this very lookup ({@code AWS_ACCESS_KEY_ID}/
     * {@code AWS_SECRET_ACCESS_KEY} — fetching those FROM Secrets Manager to authenticate TO
     * Secrets Manager is circular).
     */
    static final List<String> SECRET_VARIABLE_NAMES = List.of(
            "DB_PASSWORD",
            "JWT_SECRET",
            "PII_ENCRYPTION_KEY_BASE64",
            "PII_LOOKUP_HASH_KEY_BASE64",
            "STRIPE_SECRET_KEY",
            "STRIPE_WEBHOOK_SECRET",
            "RAZORPAY_KEY_SECRET",
            "RAZORPAY_WEBHOOK_SECRET",
            "MAIL_PASSWORD",
            "REDIS_PASSWORD");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Boolean.parseBoolean(environment.getProperty("AWS_SECRETS_MANAGER_ENABLED", "false"))) {
            return;
        }

        String region = environment.getProperty("AWS_SECRETS_MANAGER_REGION",
                environment.getProperty("AWS_REGION", ""));
        var builder = SecretsManagerClient.builder();
        if (!region.isBlank()) {
            builder = builder.region(Region.of(region));
        }

        try (SecretsManagerClient client = builder.build()) {
            apply(environment, client, region);
        }
    }

    /**
     * The actual fetch-and-inject logic, separated from client construction so tests can pass a
     * client backed by a mock/fake HTTP response instead of a real AWS Secrets Manager endpoint.
     */
    void apply(ConfigurableEnvironment environment, SecretsManagerClient client, String region) {
        String prefix = environment.getProperty("AWS_SECRETS_MANAGER_PREFIX", DEFAULT_PREFIX);
        Map<String, Object> fetched = new LinkedHashMap<>();

        try {
            for (String variableName : SECRET_VARIABLE_NAMES) {
                String secretName = prefix + variableName;
                try {
                    String value = client.getSecretValue(
                            GetSecretValueRequest.builder().secretId(secretName).build()).secretString();
                    if (value != null && !value.isBlank()) {
                        fetched.put(variableName, value);
                    }
                } catch (ResourceNotFoundException notMigratedYet) {
                    log.debug("No Secrets Manager entry for {} — falling back to env var.", secretName);
                }
            }
        } catch (SecretsManagerException | software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new IllegalStateException(
                    "AWS_SECRETS_MANAGER_ENABLED=true but Secrets Manager could not be reached "
                            + "(region='" + region + "'). Refusing to start: falling back to env vars here "
                            + "would silently defeat the point of enabling secret-manager mode. "
                            + e.getMessage(), e);
        }

        log.info("AWS Secrets Manager: sourced {} of {} tracked secrets ({}); the rest fall back to "
                        + "their env vars.", fetched.size(), SECRET_VARIABLE_NAMES.size(), fetched.keySet());

        if (!fetched.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, fetched));
        }
    }
}
