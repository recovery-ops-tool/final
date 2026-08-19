# SYSTEM 04 — Environment & Configuration Management: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 04 block, 2026-08-18 session (continuation of
the session that completed SYSTEM 41, SYSTEM 01, SYSTEM 02, SYSTEM 03).

## Prerequisite check

None.

## Scope decision made with the user before implementing

TASK 4.2's original text lists Stripe keys, Razorpay keys, and SMTP credentials as things that
should hard-fail production boot if missing, alongside DB/JWT/encryption/`TRUSTED_PROXY_CIDR`.
Investigation found `StripeConfig`, `RazorpayConfig`, and `EmailServiceImpl` already share a
deliberate, working "warn loudly, degrade gracefully, never block boot" design (`GstConfig`'s
javadoc explicitly says it mirrors Stripe/Razorpay's shape; `management.health.mail.enabled=false`
exists because of a real incident — Gmail auth failures hanging health checks under load,
2026-08-04). Confirmed with the user: the new fail-fast validator covers only `DB_PASSWORD`,
`JWT_SECRET`, the PII encryption key, and `TRUSTED_PROXY_CIDR` — not payment/mail credentials. Full
reasoning in `docs/CONFIG-REFERENCE.md` ("Why payment/mail credentials aren't boot-enforced").

Also asked, after 4.1/4.2 were done and verified: continue into TASK 4.3 (P2, AWS Secrets Manager)
in the same session, or stop with SYSTEM 04 at 2/3 and move to SYSTEM 05. User chose to continue;
4.3 is done below.

## TASK 4.1 — Env var inventory [DONE]

`docs/CONFIG-REFERENCE.md` — every one of the 73 unique `${...}` placeholders the grep in the task
finds across `application*.properties`, tabulated by category (database, Redis, JWT, CORS, PII
encryption, AWS/S3, file upload/malware scanning, mail/alerts, Stripe, Razorpay, GST, business
rules, Lucien AI, Lucien voice, networking), each with purpose/required-in-prod/default/consequence.
Plus two additions the grep can't find by construction: `REPLICA_DB_URL` (a direct `@Value` in
`RlsDataSourceConfig`, not a properties-file placeholder — pre-existing, added incidentally by
SYSTEM 02) and a new "Config surface outside the properties files" section covering four more
`@Value`s that use Spring's relaxed env-var binding with no properties-file line at all
(`APP_REGION_EXPECTED`/`APP_REGION_ENFORCE` in `DataLocalizationCheck`,
`APP_SECURITY_MFA_ENFORCE`/`APP_SECURITY_MFA_REQUIRED_ROLES` in `MfaServiceImpl`).

**Real gaps found and fixed while building this, not just documented:**

- **`server/.env.example` was significantly stale** — several variable names it documented were
  never real (`PORT`, `MAX_FILE_SIZE`, `EMAIL_FROM`, `APP_ENV`, `TIMEZONE`, `GOOGLE_CLIENT_ID`/
  `OAUTH2_*` — no OAuth2 integration exists in this codebase — `LOG_LEVEL_*`, `MANAGEMENT_PORT`,
  `AWS_S3_RELEASES_BUCKET`, `UPDATE_SECRET`, `VISIT_LOG_MAX_SIZE`, `DEEP_LINK_BASE`), and one was
  flat-out wrong: `AWS_S3_BUCKET` where the app actually reads `S3_BUCKET` — anyone who filled in
  the documented name with S3 enabled would have silently gotten the default bucket instead.
  `JWT_EXPIRATION_MS`/`REFRESH_TOKEN_EXP_DAYS` were also wrong names (real: `JWT_ACCESS_EXPIRY_MS`/
  `JWT_REFRESH_EXPIRY_MS`, and the refresh one is milliseconds, not days). Rewritten to match
  `docs/CONFIG-REFERENCE.md` exactly.
- **A dangling doc reference**: `application-prod.properties`'s header comment pointed at
  `DEPLOYMENT.md`, which does not exist anywhere in the repo. Fixed to point at
  `docs/CONFIG-REFERENCE.md` / `server/.env.example`.
- **Razorpay webhook-secret gap**: `WebhookSecretsStartupCheck` only ever checked Stripe's webhook
  secret against Stripe's API key — an active Razorpay integration (key-id/key-secret set) with a
  blank webhook secret had no equivalent check. Extended to cover both providers, with real tests
  (`WebhookSecretsStartupCheckTest`, new — the class had none before).
- **Open, flagged but not fixed this system** (see `docs/CONFIG-REFERENCE.md`'s "Open" section):
  `APP_SECURITY_MFA_ENFORCE` defaults `false` with no properties-file surface or boot-time signal
  either way — this system's own "no silently insecure default" standard's clearest remaining
  violation, deferred to SYSTEM 08 (product/rollout decision, not a fail-fast candidate).
  `CLAMAV_ENABLED=false` by default means uploads are never malware-scanned unless explicitly turned
  on with a reachable daemon (SYSTEM 05's territory).

## TASK 4.2 — Fail-fast on missing production config [DONE]

`ProductionConfigEnvironmentPostProcessor`
(`server/src/main/java/com/recoverpro/server/config/ProductionConfigEnvironmentPostProcessor.java`)
— a Spring Boot `EnvironmentPostProcessor` (registered via the new
`server/src/main/resources/META-INF/spring.factories`), not a `@PostConstruct`/`@Component` like
`JwtTokenProvider`'s existing check: it runs before the `ApplicationContext` exists at all, so if
several required vars are missing simultaneously it reports every one in a single failure instead
of the operator discovering them one redeploy at a time as whichever bean's own check happens to
construct first. Only active when `spring.profiles.active` includes `prod`. Reads raw env var names
directly (not resolved `app.*`/`spring.*` keys), so it has no dependency on Spring's own
`application*.properties` processing order.

Scope (per the decision above): `DB_PASSWORD`, `JWT_SECRET`, `PII_ENCRYPTION_KEY_BASE64` (or
`PII_ENCRYPTION_KMS_KEY_ID` if provider=kms), `TRUSTED_PROXY_CIDR`. `JWT_SECRET` and the encryption
key already had their own independent, every-profile checks (`JwtTokenProvider`, `EncryptionContext`)
before this system — not removed, since they fail even earlier (during bean construction) and are
a reasonable defense-in-depth backstop; also re-checked here specifically so they show up in the
same consolidated report as `DB_PASSWORD`/`TRUSTED_PROXY_CIDR` when more than one thing is wrong
at once. `TRUSTED_PROXY_CIDR` had no prior enforcement at all — the biggest real gap this task
closes, per SYSTEM 02's earlier finding that an unset value silently and dangerously changes what
`getRemoteAddr()` returns behind a real proxy.

**Real bug found and fixed by actually running this, not just wiring it**: verifying with a real
boot (`java -jar`, `SPRING_PROFILES_ACTIVE=prod`) surfaced a completely unrelated, pre-existing
blocker — `logback-spring.xml`'s `prod` profile block references
`net.logstash.logback.encoder.LogstashEncoder` for structured JSON console logging, but
`logstash-logback-encoder` was never added as a Maven dependency. **The prod profile could not boot
at all**, for any reason, before this fix — failing at logging initialization before reaching any
application code, including this task's own new check. Fixed: added
`net.logstash.logback:logstash-logback-encoder:8.0` to `server/pom.xml` (version chosen for
Logback 1.5.x/SLF4J 2.x compatibility, matching this Boot line's bundled `logback-classic 1.5.22`;
confirmed it resolves from Maven Central and the resulting JSON log output is well-formed — see
Verification).

## TASK 4.3 — Secret manager integration [DONE]

`AwsSecretsManagerEnvironmentPostProcessor`
(`server/src/main/java/com/recoverpro/server/config/AwsSecretsManagerEnvironmentPostProcessor.java`)
— another `EnvironmentPostProcessor`, ordered to run before TASK 4.2's check
(`Ordered.HIGHEST_PRECEDENCE + 5` vs. `+ 10`) so a secret it sources is visible to that check as
present, not missing. Off by default (`AWS_SECRETS_MANAGER_ENABLED` unset/false) — TASK 4.3.b's
explicit requirement; local dev and every existing deployment are unaffected.

When enabled, fetches each of ten tracked secret variables (see `docs/RUNBOOK-SECRETS.md` for the
full list and why each one is/isn't included) from AWS Secrets Manager under
`<AWS_SECRETS_MANAGER_PREFIX><VAR_NAME>`, injecting found values as the highest-priority property
source so every existing `${VAR_NAME:default}` placeholder resolves to it with zero changes
anywhere else. A secret not yet migrated (not found) falls back to its env var — migration is
one-variable-at-a-time, not all-or-nothing. A genuine failure to reach Secrets Manager at all (bad
credentials, network partition) is NOT swallowed the same way — refuses to start, matching TASK
4.2's "half-configured deploy is worse than one that doesn't come up" theme.

Chose AWS Secrets Manager over SSM Parameter Store (task text allowed either): more purpose-built
for secrets specifically (native rotation-Lambda integration, versioning) and a more natural fit
for `docs/RUNBOOK-SECRETS.md`'s rotation documentation. Credentials use the AWS SDK's default
provider chain (matching `KmsEnvelopeEncryptor`'s existing pattern, not `S3Config`'s explicit
static-credentials one) — works via an IAM role if compute ever moves onto AWS, not only via env
vars. On the actual OCI deployment target, there is no IAM role available, so
`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` remain a required bootstrap credential pair even with
this mode on — documented explicitly in `docs/RUNBOOK-SECRETS.md` as an inherent limit, not an
oversight: this mode's real value is reducing N secrets in the environment to one bootstrap pair,
not eliminating env vars entirely.

`docs/RUNBOOK-SECRETS.md` (TASK 4.3.c): how the mode works, rotation procedure per secret category
(most secrets: rotate at source → write to Secrets Manager → redeploy → confirm → invalidate old;
webhook secrets: must be regenerated at the provider dashboard first; `JWT_SECRET`: explicitly
**not** graceful — no key versioning exists, rotating it signs out every active session
immediately). For the PII encryption key specifically: explicitly documents that rotating it is
**not safe yet** — `EncryptionContext` has no ciphertext key-versioning, so rotating
`PII_ENCRYPTION_KEY_BASE64` today makes existing encrypted data unreadable with no way back. That
mechanism (versioned envelope + re-encryption job) is SYSTEM 07 TASK 7.2's explicitly-stated gap,
not built here — this runbook points to it rather than inventing a parallel, incomplete one.

## Verification

**TASK 4.2, real boot tests** (not just unit tests — the tasklist's own SYSTEM VERIFICATION step):
`java -jar target/recoverpro-server-1.0.0.jar`, `SPRING_PROFILES_ACTIVE=prod`.
- Missing `TRUSTED_PROXY_CIDR` only: exit code 1, single clear `IllegalStateException` naming it.
- Everything required present: Tomcat started, `Started ServerApplication in 23.308 seconds`, JSON
  structured logging confirmed well-formed (proves the `logstash-logback-encoder` fix works, not
  just compiles) — ran against this session's real local Postgres/Redis, killed after confirming a
  clean start (not left running).

**TASK 4.3, real boot test**: `AWS_SECRETS_MANAGER_ENABLED=true`, no AWS credentials in the
environment, `SPRING_PROFILES_ACTIVE=prod` — exit code 1, clear message naming every credential
provider in the chain that was tried and failed. Confirms the fail-fast path is real, not just
mocked. The fetch-success path is unit-tested against a mocked `SecretsManagerClient` only — no
live AWS Secrets Manager instance is provisioned anywhere yet (see `docs/CONFIG-REFERENCE.md`'s
Open section); re-verify against a real instance the first time this mode is actually turned on.

Full `mvn -f server/pom.xml test`: **654 tests, 0 failures, 0 errors, 2 skipped** (the same two
`StartupSchemaVersionCheckTest` cases as every prior system this session, unrelated to this work —
needs `CREATE DATABASE` privilege not present locally), **BUILD SUCCESS**. New test classes this
system: `ProductionConfigEnvironmentPostProcessorTest` (9), `WebhookSecretsStartupCheckTest` (6, a
class that previously had none), `AwsSecretsManagerEnvironmentPostProcessorTest` (7) — 22 new tests
total. Ran the full suite four times across this system (after TASK 4.2's code, after the
`pom.xml` logging fix, after TASK 4.3's code, and this final pass) rather than once at the end, per
this session's standing practice of not trusting `mvn compile` alone.
