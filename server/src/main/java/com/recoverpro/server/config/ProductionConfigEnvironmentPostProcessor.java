package com.recoverpro.server.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * SYSTEM 04 TASK 4.2 — consolidated fail-fast check for production config that is dangerous
 * when silently missing. Runs as an {@link EnvironmentPostProcessor}, before the
 * {@code ApplicationContext} exists and before any {@code @Component}/{@code @Configuration}
 * bean is constructed, specifically so that if several required variables are missing at once
 * (e.g. {@code DB_PASSWORD} AND {@code JWT_SECRET}), every problem is reported together in one
 * failure instead of the operator discovering them one redeploy at a time as each bean's own,
 * independent check happens to run first.
 * <p>
 * Reads raw environment variable names directly (not the resolved {@code app.*}/{@code spring.*}
 * property keys) so this check has no dependency on {@code application*.properties} processing
 * order — it works correctly regardless of {@link EnvironmentPostProcessor} ordering among
 * Spring Boot's own.
 * <p>
 * Deliberately scoped to only what the app cannot safely run without at all: database
 * credentials, the JWT signing secret, the PII encryption key (or KMS key id), and the trusted-
 * proxy CIDR. Stripe/Razorpay/SMTP credentials are NOT included here — see
 * docs/CONFIG-REFERENCE.md ("Why payment/mail credentials aren't boot-enforced") for why that is
 * a deliberate scope decision, not an oversight: those integrations already have their own
 * working "warn and degrade gracefully" design ({@link StripeConfig}, {@link RazorpayConfig},
 * {@code EmailServiceImpl}) that boot-blocking would contradict.
 * <p>
 * Only active when {@code spring.profiles.active} (or {@code SPRING_PROFILES_ACTIVE}) includes
 * {@code prod} — local dev and CI/test never see this check.
 * <p>
 * Ordered explicitly (not left as Spring's default {@code LOWEST_PRECEDENCE}) so it always runs
 * after {@link AwsSecretsManagerEnvironmentPostProcessor} (SYSTEM 04 TASK 4.3) — a secret sourced
 * from AWS Secrets Manager must be visible to this check as present, not flagged missing.
 */
public class ProductionConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String DOC_REF = "See docs/CONFIG-REFERENCE.md.";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean isProd = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile)) {
                isProd = true;
                break;
            }
        }
        if (!isProd) {
            return;
        }

        List<String> problems = new ArrayList<>();

        if (isBlank(environment.getProperty("DB_PASSWORD"))) {
            problems.add("spring.datasource.password (DB_PASSWORD) is not set. Refusing to start: a blank "
                    + "production database password is either a silent security hole (trust/peer auth) or an "
                    + "unclear connection failure later. " + DOC_REF);
        }

        if (isBlank(environment.getProperty("JWT_SECRET"))) {
            problems.add("app.jwt.secret (JWT_SECRET) is not set. Refusing to start: JWTs cannot be signed "
                    + "without a secret.");
        }

        addEncryptionProblems(environment, problems);

        if (isBlank(environment.getProperty("TRUSTED_PROXY_CIDR"))) {
            problems.add("server.tomcat.remoteip.internal-proxies (TRUSTED_PROXY_CIDR) is not set. Refusing "
                    + "to start: without it, X-Forwarded-For is never trusted behind the real proxy and every "
                    + "client IP collapses onto the proxy's own address, breaking login rate-limiting, audit "
                    + "IPs, and refresh-token session tracking. " + DOC_REF);
        }

        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder(
                    "Production config validation failed (" + problems.size() + " problem(s)):");
            for (String problem : problems) {
                message.append("\n  - ").append(problem);
            }
            throw new IllegalStateException(message.toString());
        }
    }

    private void addEncryptionProblems(ConfigurableEnvironment environment, List<String> problems) {
        String enabled = environment.getProperty("PII_ENCRYPTION_ENABLED", "true");
        if (!Boolean.parseBoolean(enabled)) {
            return;
        }
        String provider = environment.getProperty("PII_ENCRYPTION_PROVIDER", "local");
        String normalizedProvider = provider == null ? "local" : provider.trim().toLowerCase();

        if ("kms".equals(normalizedProvider)) {
            if (isBlank(environment.getProperty("PII_ENCRYPTION_KMS_KEY_ID"))) {
                problems.add("app.encryption.kms.key-id (PII_ENCRYPTION_KMS_KEY_ID) is not set. Refusing to "
                        + "start: app.encryption.provider=kms but no KMS key id is configured.");
            }
        } else if (isBlank(environment.getProperty("PII_ENCRYPTION_KEY_BASE64"))) {
            problems.add("app.encryption.key-base64 (PII_ENCRYPTION_KEY_BASE64) is not set. Refusing to "
                    + "start: PII encryption is enabled (app.encryption.enabled=true, provider=local) but no "
                    + "key is configured — an auto-generated ephemeral key would make encrypted data "
                    + "unreadable after every restart.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
