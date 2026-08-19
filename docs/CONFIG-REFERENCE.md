# Config reference

SYSTEM 04 TASK 4.1: every `${...}` placeholder in `server/src/main/resources/application*.properties`,
tabulated with purpose, whether it's required in production, its default, and what happens if it's
absent. Produced by:

```
grep -rhoE '\$\{[A-Z0-9_]+(:[^}]*)?\}' server/src/main/resources/application*.properties | sort -u
```

which found 73 unique placeholders (`FLYWAY_ENABLED` appears twice with different defaults across
profiles — one row below covers both). `REPLICA_DB_URL` is included as an addendum even though it
isn't grep-visible (it's a direct `@Value` in `RlsDataSourceConfig`, not a properties-file
placeholder) — see the note on that row.

**Enforcement status.** TASK 4.2 added `ProductionConfigEnvironmentPostProcessor`
(`server/src/main/java/com/recoverpro/server/config/ProductionConfigEnvironmentPostProcessor.java`),
which — only when `spring.profiles.active` includes `prod` — checks every variable below marked
**Enforced (4.2)** and refuses to boot, listing *every* violation at once, if any is missing. A few
variables were already independently enforced before this system (marked **Enforced (pre-existing)**)
by their own component; those weren't duplicated into the new consolidated check except where noted.
Stripe/Razorpay/SMTP credentials are deliberately **not** boot-enforced — see "Why payment/mail
credentials aren't boot-enforced" below.

## Database

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `DB_URL` | `spring.datasource.url` — JDBC connection string. | Yes | `jdbc:postgresql://localhost:5432/opstool` | Missing means the prod container tries to reach a Postgres on its own `localhost`, which doesn't exist there — Hikari fails to obtain a connection and boot aborts with a JDBC error. Loud, but not itemized by variable name. |
| `DB_USER` | `spring.datasource.username`. | Yes | `opstool` | Same failure mode as `DB_URL` — a wrong/missing user fails the first Hikari connection attempt with a Postgres auth error. |
| `DB_PASSWORD` | `spring.datasource.password`. | Yes | empty string | **Enforced (4.2).** Previously: blank password reaches Hikari, which either fails on connect (if the DB requires a password) or silently succeeds (if the DB role has no password / trust auth) — the second case is a silent security hole, not a loud failure. Now refused at boot in production. |
| `DB_POOL_SIZE` | Hikari `maximum-pool-size`. SYSTEM 02 TASK 2.3 found this documented in `server/.env.example` but never actually wired to anything — fixed. | No — sane default | `20` | Existing, reasonable default applies. Revisit once `docs/DB-HOTSPOTS.md` has production-scale contention evidence. |
| `DB_MIN_IDLE` | Hikari `minimum-idle`. Same TASK 2.3 fix as above. | No — sane default | `5` | Existing, reasonable default applies. |
| `DB_CONNECTION_TIMEOUT_MS` | Hikari `connection-timeout`. New in TASK 2.3 — no prior config surface existed for it. | No — sane default | `30000` | Existing, reasonable default applies. |
| `REPLICA_DB_URL` | SYSTEM 02 TASK 2.4 — when set, routes `ExportServiceImpl.findReportJob` (and any future call wrapped in `ReplicaRoutingContext.runOnReplica`) to a second Hikari pool against this URL, wrapped in its own `RlsAwareDataSource` (never route to a bare, unwrapped pool — see `RlsDataSourceConfig`'s javadoc). Not a properties-file placeholder — read directly via `@Value("${REPLICA_DB_URL:${spring.datasource.url}}")` in `RlsDataSourceConfig`, which is why the grep above doesn't surface it. | No — no real replica exists yet (no OCI instance provisioned, see `docs/INFRA-CURRENT.md`) | unset → resolves to the same value as `DB_URL`, reusing the same pool object rather than opening a redundant second one | Missing means replica-routed calls silently run against primary — correct, safe, and the explicitly designed fallback (SYSTEM 02's acceptance criterion: "with `REPLICA_DB_URL` unset, behaviour is byte-identical to today"). Not a failure mode. |
| `FLYWAY_ENABLED` | `spring.flyway.enabled`. SYSTEM 03 TASK 3.2: migrations run as an explicit pre-deploy step in production (`docs/RUNBOOK-DEPLOY.md`), not implicitly at app boot, so one bad migration can't take every instance down simultaneously. | No | `true` in `application.properties` (local/CI), overridden to `false` in `application-prod.properties` | Missing just means the profile's own default applies — not a failure mode. Can still force either environment on/off explicitly if genuinely needed. |

## Redis

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `REDIS_HOST` | `spring.data.redis.host` — session/cache/rate-limit/live-track backing store. | Yes | `localhost` | Prod container has no local Redis — every Redis-backed feature (caching, Lucien rate limiting, live-track) fails at first use, not at boot (Redis connections are lazy). Failures surface gradually per-feature rather than as one clear startup error. |
| `REDIS_PORT` | `spring.data.redis.port`. | Yes (if `REDIS_HOST` is remote) | `6379` | Standard port; wrong value fails the same way as `REDIS_HOST`. |
| `REDIS_PASSWORD` | `spring.data.redis.password`. | Depends on the Redis instance's own auth config | empty string | If the target Redis requires auth and this is blank, connections fail at first use (same lazy-failure profile as above) with an auth error. |

## JWT / password hashing

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `JWT_SECRET` | `app.jwt.secret` — HMAC signing key for access/refresh tokens. | **Yes** | empty string | **Enforced (pre-existing).** `JwtTokenProvider`'s constructor already refuses to boot (in *every* profile, not just prod) on blank, on the known placeholder string, or on fewer than 256 bits — the model this system's other checks follow. Not duplicated into the new consolidated check: it fails during regular bean construction, which happens *before* `ApplicationReadyEvent`-based checks would even run, so duplicating it there would never add information — but see the note on `ProductionConfigEnvironmentPostProcessor` below for why a blank secret is *also* included in the new check anyway (it runs even earlier, so a blank `JWT_SECRET` and a blank `DB_PASSWORD` at the same time are now reported together instead of one redeploy-cycle apart). |
| `JWT_ACCESS_EXPIRY_MS` | `app.jwt.access-token-expiry-ms`. | No — sane default | `900000` (15 min) | Existing, reasonable default applies. |
| `JWT_REFRESH_EXPIRY_MS` | `app.jwt.refresh-token-expiry-ms`. | No — sane default | `604800000` (7 days) | Existing, reasonable default applies. |
| `BCRYPT_STRENGTH` | `app.security.bcrypt-strength`, consumed by `SecurityConfig#passwordEncoder` (`MigratingPasswordEncoder`). | No — sane default | `12` | Existing, reasonable default applies (12 rounds is a solid 2026 baseline). |

## CORS

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `CORS_ORIGINS` | `app.cors.allowed-origins` — read by both `SecurityConfig` (REST) and `WebSocketConfig` (STOMP). | **Yes**, once the real frontend is served from anything other than `localhost` | `http://localhost:5173,http://localhost:3000` | Not silent — every cross-origin request from the real frontend is rejected by the browser with a visible CORS error, and every WebSocket handshake from it is rejected too. Loud and immediate, just not a boot-time failure. |

## PII field-level encryption

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `PII_ENCRYPTION_ENABLED` | `app.encryption.enabled` — master switch. | Yes, must stay `true` | `true` | If explicitly set `false`, `EncryptionContext` logs a warning and installs a `DisabledEnvelopeEncryptor` — not silent, but also not blocked. Genuinely disabling PII encryption in production is a real regression; nothing currently stops it beyond the warning log. |
| `PII_ENCRYPTION_PROVIDER` | `app.encryption.provider` — `local` (AES-256-GCM with a static key) or `kms` (AWS KMS envelope encryption). | Yes | `local` | An unrecognized value throws at boot (`EncryptionContext` bean method), in every profile. Not silent. |
| `PII_ENCRYPTION_KEY_BASE64` | AES-256-GCM key, required when provider is `local`. This is the **current** key version's material — see the two rows below for rotation. | **Yes, if provider=local** | empty string | **Enforced (pre-existing, and re-checked in 4.2's consolidated report).** `EncryptionContext`'s `@Bean` method already throws in *every* profile if this is blank while provider=local — stricter than prod-only. Also included in `ProductionConfigEnvironmentPostProcessor`'s report so it appears alongside any other missing prod var in one combined list instead of its own isolated failure. |
| `PII_ENCRYPTION_CURRENT_KEY_VERSION` | `app.encryption.current-key-version` — which version number `PII_ENCRYPTION_KEY_BASE64` actually is (SYSTEM 07 TASK 7.2). | No — sane default | `1` | Existing default applies; a fresh deployment that's never rotated is implicitly version 1. Only needs setting explicitly once a rotation happens — see `docs/RUNBOOK-KEY-ROTATION.md`. |
| `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64` | `app.encryption.previous-keys-base64` — retired key versions still needed to decrypt rows a rotation hasn't reached yet, format `v:base64key,v:base64key,...`. | Only once a rotation has happened | empty string | Empty is correct until the first rotation. `EncryptionContext` refuses to start if a version appears here *and* in `PII_ENCRYPTION_CURRENT_KEY_VERSION` at once (ambiguous config, not silently resolved one way or the other). See `docs/RUNBOOK-KEY-ROTATION.md` for the full procedure. |
| `PII_ENCRYPTION_KMS_KEY_ID` | AWS KMS key id, required when provider is `kms`. | **Yes, if provider=kms** | empty string | Same as above — `EncryptionContext` already throws in every profile if provider=kms and this is blank. Also included in the 4.2 consolidated report. |
| `PII_ENCRYPTION_KMS_REGION` | AWS region for the KMS key. | Only relevant if provider=kms | empty string | Not independently validated — an empty region with provider=kms is passed straight to `KmsEnvelopeEncryptor`, which fails on the first real KMS call, not at boot. Narrower gap than the key-id case; not added to the consolidated check since it only matters for a mode (`kms`) that isn't in use yet (`local` is the only provider currently configured anywhere). |
| `PII_LOOKUP_HASH_KEY_BASE64` | `app.encryption.lookup-hash-key` — separate HMAC key for `LookupHashService`'s encrypted-field search (duplicate-borrower detection by phone/email/CKYC ID). | Recommended, not required | empty string | Not silent: `LookupHashService` logs a warning on every boot and derives this key from `PII_ENCRYPTION_KEY_BASE64` instead — a working but weaker fallback (reused key material across purposes). Deliberately not boot-blocking. |
| `LOOKUP_HASH_BACKFILL` | `app.backfill.lookup-hash` — one-time switch for `LookupHashBackfillRunner`, used to recompute existing lookup hashes after rotating `PII_LOOKUP_HASH_KEY_BASE64`. | No | `false` | Leave `false` always except for exactly one deliberate run right after a key rotation; missing/`false` is the correct steady state. |

## AWS / S3

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `AWS_S3_ENABLED` | `aws.s3.enabled` — gates `S3Config`'s beans (`@ConditionalOnProperty`) and `S3OrLocalStoragePort`'s storage target. | Recommended `true` once deployed (see `docs/RUNBOOK-DR.md` TASK 41.2b) | `false` | `false` means uploads/reports go to local disk instead of S3 — does not survive a container restart or scale past one instance. Not a boot failure, a durability gap. |
| `AWS_REGION` | `aws.region`, used by `S3Config` (only loaded when `AWS_S3_ENABLED=true`) and `DataLocalizationCheck` (`app.region.expected` — see "Config surface outside the properties files" below). | Yes, if `AWS_S3_ENABLED=true` | `ap-south-1` | If S3 is enabled and the region is wrong, `S3Client`/`S3Presigner` calls fail at first use with a region/endpoint error — not at boot (`S3Config`'s beans build successfully with any syntactically valid region string). |
| `S3_BUCKET` | `aws.s3.bucket`. | Yes, if `AWS_S3_ENABLED=true` | `ops-tool-documents` | Not independently validated; a wrong bucket name fails S3 calls at first use with an AWS "no such bucket" error. **Note:** `server/.env.example` previously documented this as `AWS_S3_BUCKET` — the wrong name, which would have had zero effect if someone actually set it. Fixed as part of this system (see "server/.env.example was stale" below). |
| `AWS_ACCESS_KEY_ID` | `aws.access-key-id`. | Yes, if `AWS_S3_ENABLED=true` | empty string | Blank reaches `StaticCredentialsProvider` and fails at first real S3 call with an AWS auth error, not at boot. Gated behind the `AWS_S3_ENABLED` opt-in, so not currently a live gap (S3 is off by default). |
| `AWS_SECRET_ACCESS_KEY` | `aws.secret-access-key`. | Yes, if `AWS_S3_ENABLED=true` | empty string | Same as `AWS_ACCESS_KEY_ID`. |
| `AWS_S3_ENDPOINT_OVERRIDE` | `aws.s3.endpoint-override` — when set, points `S3Client`/`S3Presigner` at an S3-compatible endpoint (e.g. OCI Object Storage) instead of real AWS (path-style addressing forced automatically). Added by SYSTEM 41 (`docs/RUNBOOK-DR.md`) so uploads/reports can live in durable OCI Object Storage instead of the single app VM's local disk. | Recommended once deployed (`docs/RUNBOOK-DR.md` TASK 41.2b) | empty string (real AWS S3 if `AWS_S3_ENABLED=true`) | Missing/empty with `AWS_S3_ENABLED=true` just means real AWS S3 is used — a safe, valid choice, not a failure mode. The actual risk this variable exists to close is `AWS_S3_ENABLED=false` (the default) — see that row. |

## File upload / storage / malware scanning

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `UPLOAD_DIR` | Base directory for local-disk storage (used when `AWS_S3_ENABLED=false`); every `app.storage.*-path` property is a subdirectory of it. Previously did nothing — each service had its own independent, unset-by-any-env-var relative default; fixed so one variable actually controls all of them. | Yes, if running with local-disk storage (i.e. `AWS_S3_ENABLED=false`) | `./uploads` (relative to the process's working directory) | A relative default on a container redeploy or scale-out means uploads land in a fresh, non-persistent directory each time — same durability gap as `AWS_S3_ENABLED=false` generally, compounded by "relative path" specifically. |
| `REPORTS_DIR` | Separate directory for generated reports (PDF/Excel exports) — kept distinct from `UPLOAD_DIR` since nothing is "uploaded" here. | Same as `UPLOAD_DIR` | `./reports` | Same relative-path durability gap as `UPLOAD_DIR`. |
| `FILE_MAX_SIZE_BYTES` | `application.file.max-size-bytes`, enforced by `FileValidationServiceImpl`. | No — sane default | `20971520` (20 MB) | Existing, reasonable default applies. Note: `spring.servlet.multipart.max-file-size`/`max-request-size` are separately hardcoded to `20MB` in `application.properties` with no env var at all — keep both in sync by hand if this is ever changed. |
| `FILE_ALLOWED_TYPES` | `application.file.allowed-types`, enforced by `FileValidationServiceImpl` as an allow-list of MIME types. | No — sane default | `text/csv,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Existing, reasonable default applies (spreadsheet formats, matching the app's bulk-import use case). |
| `CLAMAV_ENABLED` | `clamav.enabled`, gates `ClamAvScannerClient.isClean()`. | **Recommended `true` in production — not currently enforced** | `false` | When `false` (the default), uploaded files are **never scanned for malware at all** — `isClean()` returns `true` unconditionally without touching the network. This is a real, currently-open production gap for a document-handling SaaS; see "Open" below. Deliberately not added to the 4.2 fail-fast check (it's a security *posture* choice, not a "missing secret," and forcing it on would require a reachable ClamAV daemon to exist first — an infra dependency, SYSTEM 05's territory). |
| `CLAMAV_HOST` | `clamav.host`. | Yes, if `CLAMAV_ENABLED=true` | `localhost` | If enabled with an unreachable host, `ClamAvScannerClient` catches the connection failure and treats the file as **infected** (fail-closed) — uploads are rejected, loudly, per-file. Not a boot failure, and the fail-closed direction is the safe one. |
| `CLAMAV_PORT` | `clamav.port`. | Yes, if `CLAMAV_ENABLED=true` | `3310` | Same fail-closed behavior as `CLAMAV_HOST`. |
| `CLAMAV_TIMEOUT_MS` | `clamav.timeout-ms`. | No — sane default | `10000` | Existing, reasonable default applies. |

## Mail / ops alerts

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `MAIL_HOST` | `spring.mail.host`. | Recommended, not boot-enforced — see "Why payment/mail credentials aren't boot-enforced" | `smtp.gmail.com` | Mail is deliberately best-effort everywhere in this app: `EmailServiceImpl` logs a warning and skips sending rather than failing a caller when mail is unconfigured or rejects auth. `management.health.mail.enabled=false` exists specifically because the default Spring mail health indicator opened a real SMTP connection on every `/health` call, and a rejected Gmail account (found via load testing, 2026-08-04) made every health check hang for seconds — degrading the whole app under routine health polling, since Tomcat threads are shared across all endpoints. |
| `MAIL_PORT` | `spring.mail.port`. | Same as `MAIL_HOST` | `587` | Same best-effort behavior. |
| `MAIL_USERNAME` | `spring.mail.username`. | Same as `MAIL_HOST` | empty string | Same best-effort behavior. |
| `MAIL_PASSWORD` | `spring.mail.password`. | Same as `MAIL_HOST` | empty string | Same best-effort behavior. |
| `CONTACT_EMAIL_RECIPIENT` | `app.mail.contact-recipient` — internal recipient for the public contact-us form. | Recommended | empty string | `EmailServiceImpl` logs a warning and skips sending rather than guessing an address — deliberately, not a bug. |
| `OPS_ALERT_RECIPIENT` | `app.alerts.ops-recipient` — who gets emailed when a background job (reconciliation, snapshots, PTP sweep, partition maintenance) fails. Falls back to `CONTACT_EMAIL_RECIPIENT` if unset. | Recommended — this is the entire alerting story for background-job failures (no dashboards/metrics stack watches these) | empty string | If neither this nor `CONTACT_EMAIL_RECIPIENT` is set, `OpsAlertService` logs a warning and skips sending — a dead scheduled job then only ever shows up in the log. `server/.env.example` already flags this exact risk ("how the reconciliation scheduler died silently once"). |
| `OPS_ALERT_COOLDOWN_MINUTES` | `app.alerts.cooldown-minutes` — minimum minutes between two alert emails for the same job, so a job stuck in a retry loop sends one email, not one per attempt. | No — sane default | `30` | Existing, reasonable default applies. |

## Payments — Stripe

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `STRIPE_SECRET_KEY` | `app.stripe.secret-key`. | Recommended, not boot-enforced — see below | empty string | `StripeConfig#init` only sets `Stripe.apiKey` if non-blank; blank means every Stripe call fails at the SDK level when actually invoked, not at boot. |
| `STRIPE_PUBLISHABLE_KEY` | `app.stripe.publishable-key`, exposed to the frontend for Stripe Elements. | Recommended, not boot-enforced | empty string | Frontend Stripe checkout UI fails to initialize; a frontend-visible failure, not a backend one. |
| `STRIPE_WEBHOOK_SECRET` | `app.stripe.webhook-secret`. | **Enforced (pre-existing) — conditionally.** | empty string | `WebhookSecretsStartupCheck` already refuses to boot, in every profile, if `STRIPE_SECRET_KEY` is set but this is blank (signature verification can't fail closed with no secret). If `STRIPE_SECRET_KEY` is also blank, this check doesn't fire — consistent with Stripe being fully optional until a secret key exists. |
| `STRIPE_TRIAL_DAYS` | `app.stripe.trial-days`. | No — sane default | `14` | Existing, reasonable default applies. |
| `STRIPE_PRICE_STARTER` / `STRIPE_PRICE_GROWTH` / `STRIPE_PRICE_ENTERPRISE` | `app.stripe.price.{starter,growth,enterprise}` — Stripe Price ids, created ahead of time via Stripe dashboard/API. | Recommended, if using Stripe for that plan tier | empty string | Checkout for that tier fails at call time with a Stripe "no such price" error, not at boot. |
| `APP_BASE_URL` | `app.base-url` — used by both `StripeConfig` and `RazorpayConfig` for checkout success/cancel redirect URLs. | Yes, once either payment provider is live | `http://localhost:3000` | Checkout redirects send the customer back to `localhost` instead of the real app — breaks the payment flow visibly, not a boot failure. |

## Payments — Razorpay

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `RAZORPAY_KEY_ID` | `app.razorpay.key-id`. | Recommended, not boot-enforced | empty string | `RazorpayConfig#init` logs a warning and leaves `client` null if either key-id or key-secret is blank; `RazorpayPaymentProvider` checks for null and fails calls at invocation time, not at boot. |
| `RAZORPAY_KEY_SECRET` | `app.razorpay.key-secret`. | Recommended, not boot-enforced | empty string | Same as `RAZORPAY_KEY_ID`. |
| `RAZORPAY_WEBHOOK_SECRET` | `app.razorpay.webhook-secret`. | Recommended | empty string | **Gap found and fixed in this system:** unlike Stripe, nothing previously checked whether an active Razorpay integration (key-id/key-secret set) had a blank webhook secret — `WebhookSecretsStartupCheck` only covered Stripe. Extended in this system to cover Razorpay too, mirroring the exact same logic. |
| `RAZORPAY_TRIAL_DAYS` | `app.razorpay.trial-days`. | No — sane default | `14` | Existing, reasonable default applies. |
| `RAZORPAY_PLAN_STARTER` / `RAZORPAY_PLAN_GROWTH` / `RAZORPAY_PLAN_ENTERPRISE` | `app.razorpay.plan.{starter,growth,enterprise}` — Razorpay Plan ids, created ahead of time via Razorpay dashboard/API. | Recommended, if using Razorpay for that plan tier | empty string | Subscription creation for that tier fails at call time with a Razorpay "plan not found" error, not at boot. |

## GST (RecoverPro's own SaaS invoices)

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `GST_SUPPLIER_GSTIN` | `app.gst.supplier-gstin` — RecoverPro's own GSTIN. | Recommended, once invoicing Indian customers | empty string | Not silent: `GstConfig#init` logs a warning and `GstInvoiceLineItemService` refuses to run until it's set to a structurally valid GSTIN — deliberate, matches the Stripe/Razorpay "warn, don't crash" pattern. |
| `GST_DEFAULT_RATE_BPS` | `app.gst.default-rate-bps` — 1800 = 18%. Explicitly a placeholder pending accountant confirmation (see `GstCalculator`'s javadoc), not an asserted-correct figure. | No | `1800` | Existing default applies; revisit with real accounting sign-off before this is load-bearing. |

## Business rules

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `GRIEVANCE_ACK_SLA_DAYS` | `app.grievance.acknowledgement-sla-days` — this org's own SLA commitment, not a cited RBI figure (the lucien-corpus flags the actual regulatory response-time SLA as unverified). | No | `3` | Existing default applies. |
| `GRIEVANCE_RESOLUTION_SLA_DAYS` | `app.grievance.resolution-sla-days`. | No | `30` | Existing default applies. |
| `SETTLEMENT_COMPLIANCE_THRESHOLD_PCT` | `app.settlement.compliance-review-discount-threshold-pct` — discount % above which a settlement offer requires ORG_ADMIN/PLATFORM_ADMIN approval instead of TL/MANAGER. | No | `30` | Existing default applies — a business-policy number, not a security control. |
| `MIS_EOD_CRON` | `app.scheduler.mis-eod-cron` — cron expression for the end-of-day MIS scheduler. | No | `0 0 20 * * *` (8 PM daily) | Existing default applies. |

## Lucien AI (Ollama / Llama)

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `LLAMA_BASE_URL` | `lucien.llama.base-url` — Ollama endpoint. | Yes, for Lucien chat to work at all | `http://localhost:11434` | Not a boot failure — `LlamaClient` calls fail at request time, and the `llama` circuit breaker (configured in `application.properties`) trips open after repeated failures, degrading Lucien gracefully rather than taking the app down. |
| `LLAMA_MODEL` | `lucien.llama.model`. | Yes, for Lucien chat | `llama3` | Same graceful-degradation profile as `LLAMA_BASE_URL` — a wrong model name fails Ollama calls at request time. |
| `LLAMA_EMBEDDING_MODEL` | `lucien.llama.embedding-model` — used for RAG document embedding. | Yes, for Lucien's RAG/document search | `nomic-embed-text` | Same graceful-degradation profile. |
| `LLAMA_MAX_TOKENS` | `lucien.llama.max-tokens`. | No — sane default | `1024` | Existing default applies. |
| `LLAMA_TEMPERATURE` | `lucien.llama.temperature`. | No — sane default | `0.7` | Existing default applies. |
| `LLAMA_TOP_P` | `lucien.llama.top-p`. | No — sane default | `0.9` | Existing default applies. |
| `LLAMA_CONNECT_TIMEOUT_MS` | `lucien.llama.connect-timeout-ms`. | No — sane default | `5000` | Existing default applies. |
| `LLAMA_READ_TIMEOUT_MS` | `lucien.llama.read-timeout-ms`. Kept just under the `llama` circuit breaker's `slow-call-duration-threshold` (280000ms) so only calls near the real timeout count as slow — a CPU-only 8B model routinely takes 100–250s per reply. | No — sane default, but coupled to the hardcoded circuit-breaker threshold above it | `300000` (5 min) | Existing default applies; if this is ever changed, `resilience4j.circuitbreaker.instances.llama.slow-call-duration-threshold` in `application.properties` (hardcoded, no env var) needs to move with it. |
| `LUCIEN_AGENT_MAX_ITERATIONS` | `lucien.agent.max-iterations` — cap on Lucien's agentic tool-call loop. | No — sane default | `8` | Existing default applies. |
| `LUCIEN_RATE_LIMIT_MAX` | `lucien.rate-limit.max-requests`. | No — sane default | `20` | Existing default applies. |
| `LUCIEN_RATE_LIMIT_WINDOW` | `lucien.rate-limit.window-seconds`. | No — sane default | `60` | Existing default applies. |

## Lucien voice (TTS/STT microservice)

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `TTS_BASE_URL` | `lucien.tts.base-url` — shared base URL for both `TtsClient` and `SttClient` (`tts-service/`, FastAPI, wraps IndicF5 for TTS and faster-whisper for STT). STT backs the composer's mic button and is actively used; TTS (~8–9 min/reply on CPU per the code comment) is wired but not currently called from the frontend (`LucienPanel` always uses the browser's own voice). | Yes, for the mic-dictation feature | `http://localhost:8100` | STT calls fail at request time; the `stt` circuit breaker degrades gracefully rather than crashing. Per project memory, the confirmed AI backend direction is Sarvam AI via API (superseding this local RunPod/self-host path) — this variable's real-world relevance may already be superseded; worth confirming against current Lucien architecture before treating it as load-bearing. |
| `TTS_CONNECT_TIMEOUT_MS` | `lucien.tts.connect-timeout-ms`. | No — sane default | `5000` | Existing default applies. |
| `TTS_READ_TIMEOUT_MS` | `lucien.tts.read-timeout-ms`. | No — sane default | `60000` | Existing default applies. |

## Networking

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `TRUSTED_PROXY_CIDR` | `server.tomcat.remoteip.internal-proxies` — the CIDR(s) Tomcat's `RemoteIpValve` trusts to supply `X-Forwarded-For`. Everything downstream (`request.getRemoteAddr()`, and every caller of `ClientIpResolver.resolve()` — `AuthServiceImpl` login rate-limiting/audit, `RefreshTokenRotationServiceImpl` session IP/theft-detection, `ContactController` rate-limiting) depends on this being correct. | **Yes**, once deployed behind any reverse proxy/load balancer (OCI + Caddy, per `docs/INFRA-CURRENT.md`) | empty string | **Enforced (4.2).** Previously: empty meant `internal-proxies` fell back to Tomcat's built-in private-range regex — if the real proxy's address fell outside that range, `X-Forwarded-For` was never trusted and `getRemoteAddr()` returned the *proxy's* IP for every request, collapsing every login rate-limit bucket, audit-log IP, and refresh-token session IP onto one shared value (a different failure mode than the original spoofing bug, but still broken), entirely silently. Now refused at boot in production. Still not yet measured against a live OCI+Caddy deployment — SYSTEM 05's provisioning work must record the actual proxy IP/CIDR here once it exists, and re-verify `ClientIpResolver.resolve()` against the real deployment. |

## Secrets manager (SYSTEM 04 TASK 4.3)

| Variable | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|
| `AWS_SECRETS_MANAGER_ENABLED` | Master switch for `AwsSecretsManagerEnvironmentPostProcessor` — when true, sources the secret variables listed in `docs/RUNBOOK-SECRETS.md` from AWS Secrets Manager instead of their own env vars, falling back per-variable to the env var for anything not found. Not a properties-file placeholder — read directly off the raw environment in an `EnvironmentPostProcessor`, same category as `REPLICA_DB_URL` above. | No | `false` | Missing/false means every secret continues to come from its own env var exactly as before — the pre-TASK-4.3 behavior, unaffected. |
| `AWS_SECRETS_MANAGER_REGION` | AWS region to query. Falls back to `AWS_REGION` if unset. | Only relevant if `AWS_SECRETS_MANAGER_ENABLED=true` | falls back to `AWS_REGION` | N/A when the master switch is off. |
| `AWS_SECRETS_MANAGER_PREFIX` | Secret-name prefix — a variable named `JWT_SECRET` is looked up as `<prefix>JWT_SECRET`. | No | `recoverpro/` | Existing default applies. |

Full rotation procedure per secret is in `docs/RUNBOOK-SECRETS.md`. `PII_ENCRYPTION_KEY_BASE64`
has its own dedicated procedure, `docs/RUNBOOK-KEY-ROTATION.md` (SYSTEM 07 TASK 7.2, done);
`PII_LOOKUP_HASH_KEY_BASE64` is still not safely rotatable without a full-outage backfill window —
same doc, "Separate, harder problem" section.

## Config surface outside the properties files

A handful of Spring `@Value` injections use Spring Boot's relaxed environment-variable binding
(`APP_FOO_BAR` → `app.foo.bar`) directly in Java code, with **no** corresponding `${ENV_VAR:...}`
line in any `application*.properties` file — so they're real, working production configuration
knobs that the grep in TASK 4.1 cannot find by construction. Found by grepping `@Value("${app...`
and `@Value("${aws...` etc. across `server/src/main/java` and checking which ones have no properties-
file backing:

| Variable (relaxed-binding form) | Property key | Purpose | Required in production | Default | Consequence if missing |
|---|---|---|---|---|---|
| `APP_REGION_EXPECTED` | `app.region.expected`, in `DataLocalizationCheck` | Expected AWS region for data-localization compliance — compared against the resolved `AWS_REGION` at boot. | Recommended | empty string | If unset, `DataLocalizationCheck` logs "check skipped" and returns — not enforced. Data-localization compliance is currently unverified in production by default. |
| `APP_REGION_ENFORCE` | `app.region.enforce`, in `DataLocalizationCheck` | Whether a region mismatch (when `APP_REGION_EXPECTED` *is* set) throws at boot or just logs an error. | Yes (if `APP_REGION_EXPECTED` is set) | `true` (fail-safe — the risky direction, `false`, requires an explicit opt-out) | Correctly fail-safe already; no action needed. |
| `APP_SECURITY_MFA_ENFORCE` | `app.security.mfa.enforce`, in `MfaServiceImpl` | Whether MFA enrollment is globally required before login for the roles in `APP_SECURITY_MFA_REQUIRED_ROLES`. | **Recommended `true` in production — currently defaults off** | `false` | This is the "no production default that is silently insecure" case this system's PRODUCTION STANDARD section calls out directly: MFA enforcement is off by default, with no properties-file line surfacing it as a decision anyone has to make, and no boot-time warning either way. Flagged here as an open item — see "Open" below; not added to the 4.2 fail-fast check since forcing MFA on is a product/rollout decision (existing users would be locked out without enrollment first), not a "missing secret." |
| `APP_SECURITY_MFA_REQUIRED_ROLES` | `app.security.mfa.required-roles`, in `MfaServiceImpl` | CSV of roles MFA applies to when enforcement is on. | No — sane default | `ROLE_PLATFORM_ADMIN,ROLE_ORG_ADMIN` | Existing default applies. |

## Why payment/mail credentials aren't boot-enforced

TASK 4.2's original scope listed Stripe keys, Razorpay keys, and SMTP credentials alongside
DB/JWT/encryption/`TRUSTED_PROXY_CIDR` as things that should hard-fail production boot if missing.
Investigated and deliberately scoped out, confirmed with the user: `StripeConfig`, `RazorpayConfig`,
and `EmailServiceImpl` all independently converged on the same "warn loudly, degrade gracefully,
never block boot" design — `GstConfig`'s javadoc explicitly says it "mirrors `StripeConfig`/
`RazorpayConfig`'s shape," and `management.health.mail.enabled=false` exists because of a real
production incident (Gmail auth failures hanging health checks under load, 2026-08-04). Making these
boot-blocking would contradict working, deliberately-designed behavior and would prevent a legitimate
deploy that only uses one payment provider, or that isn't accepting payments yet, from starting at
all. The consolidated fail-fast validator (`ProductionConfigEnvironmentPostProcessor`) therefore only
covers: `DB_PASSWORD`, `JWT_SECRET`, `PII_ENCRYPTION_KEY_BASE64`/`PII_ENCRYPTION_KMS_KEY_ID`
(conditional on provider), and `TRUSTED_PROXY_CIDR`.

## `server/.env.example` was stale

While building this inventory, `server/.env.example` (the file a new deploy is meant to copy and
fill in) turned out to be significantly out of date against what the app actually reads — some
entries reference variable names that were never real (`PORT`, `MAX_FILE_SIZE`, `EMAIL_FROM`,
`APP_ENV`, `TIMEZONE`, `GOOGLE_CLIENT_ID`/`OAUTH2_*` — no OAuth2 integration exists in this codebase
— `LOG_LEVEL_*`, `MANAGEMENT_PORT`, `AWS_S3_RELEASES_BUCKET`, `UPDATE_SECRET`, `VISIT_LOG_MAX_SIZE`,
`DEEP_LINK_BASE`), and one entry had the **wrong name entirely**: `AWS_S3_BUCKET` where the real
variable the app reads is `S3_BUCKET` — anyone who copied `.env.example`, filled in
`AWS_S3_BUCKET`, and enabled S3 would have silently gotten the default bucket name instead.
`JWT_EXPIRATION_MS`/`REFRESH_TOKEN_EXP_DAYS` were also wrong (real names: `JWT_ACCESS_EXPIRY_MS`,
`JWT_REFRESH_EXPIRY_MS`, and the refresh one is in milliseconds, not days). Rewritten as part of
this system to match this document exactly — see `server/.env.example`.

## Open

- **`APP_SECURITY_MFA_ENFORCE` defaults to `false`** with no properties-file surface and no boot-
  time signal either way — the clearest remaining "silently insecure default" in the app per this
  system's own PRODUCTION STANDARD. Enabling it is a product/rollout decision (needs an enrollment
  path for existing admins first), not something this system should flip unilaterally — flagged for
  a deliberate decision, likely alongside SYSTEM 08 (Authentication, currently 1/4 done).
- **`CLAMAV_ENABLED=false` by default** means uploaded documents are never scanned for malware in
  production unless someone explicitly turns this on *and* provisions a reachable ClamAV daemon
  (SYSTEM 05's territory). Currently an open gap for a document-handling SaaS.
- Actual `TRUSTED_PROXY_CIDR` value for the OCI+Caddy deployment — still not set as an env var
  (no instance provisioned yet), but as of SYSTEM 05 TASK 5.1 the target subnet is known:
  `docker-compose.yml` pins its bridge network to `172.28.0.0/16` (top-level
  `networks.default.ipam.config`).
  **Despite the variable's name, `TRUSTED_PROXY_CIDR` is NOT CIDR notation** — it feeds directly
  into `server.tomcat.remoteip.internal-proxies`, which Tomcat's `RemoteIpValve` compiles with
  `java.util.regex.Pattern.compile(...)` and matches against the literal `getRemoteAddr()` string
  (confirmed by disassembling `RemoteIpValve.class` — SYSTEM 07 TASK 7.1, while verifying HSTS
  behavior behind a reverse proxy). A literal `172.28.0.0/16` value **never matches any real IP**
  (regex `.` matches any single character, harmless, but the literal trailing `/16` requires the
  address string to end in that exact text, which no IP ever does) — the correct value for this
  subnet is the regex **`TRUSTED_PROXY_CIDR=172\.28\.\d{1,3}\.\d{1,3}`**. Whoever runs SYSTEM 05's
  provisioning task must set this exact regex form as the env var on the live instance, then
  re-verify `ClientIpResolver.resolve()` actually returns real client IPs (not Caddy's/nginx's)
  against the live deployment — a value that looks plausible but silently never matches is exactly
  the failure mode worth testing for, not assuming away.
- `PII_ENCRYPTION_KMS_REGION`/`PII_ENCRYPTION_KMS_KEY_ID` and the `kms` provider path are
  unexercised in practice (only `local` is configured anywhere) — worth a real test the first time
  a `kms` deployment is attempted, not just a boot-time null check.
- `TTS_BASE_URL`'s self-hosted FastAPI microservice may already be superseded by the Sarvam AI API
  path per project memory (`project_recoverpro_lucien_ambient_voice`) — worth confirming with
  whoever owns Lucien voice before treating it as current production config.
