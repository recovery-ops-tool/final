# SYSTEM 07 — Security (crypto, headers, input): execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 07 block (LAYER 1, P0), 2026-08-18 session.

## Prerequisite check

SYSTEM 04 (config fail-fast) — done, per `docs/SYSTEM-04-ENVIRONMENT-CONFIG-MANAGEMENT.md`.

## TASK 7.1 — Security response headers [DONE]

`SecurityConfig.java`'s `.headers(...)` DSL now explicitly sets HSTS (2-year max-age,
includeSubDomains, preload — matching `web/nginx.conf`'s existing value), Referrer-Policy
(`strict-origin-when-cross-origin`), and Permissions-Policy (everything denied on this layer —
the API/Swagger surface never itself renders a page that would call `getUserMedia`/geolocation).
X-Content-Type-Options and X-Frame-Options were already Spring Security defaults, confirmed via a
live curl before touching anything.

CSP on the Spring side is **report-only**, deliberately not enforced: `web/nginx.conf` already had
a comprehensive, *enforced* CSP from a prior session (fonts, OpenStreetMap/Carto tiles, S3 images,
same-origin API/WS via the nginx proxy) — that's the layer that actually matters, since nginx
serves the SPA's HTML document and Spring Boot only ever returns JSON (plus, non-prod only,
Swagger UI). Enforcing a second CSP on the API layer without live-traffic evidence for what
Swagger's bundled assets need risks breaking that dev tool for no real security gain on pure-JSON
responses — so it stays report-only there.

`web/nginx.conf` got one addition: Permissions-Policy (missing entirely before this task).
Checked actual usage before deciding the policy, not guessed: `geolocation`/`microphone` allowed
for `self` (grep-confirmed real use — field-visit GPS tracking across `StartVisitPage`,
`VisitInterviewPage`, `VisitSubmitPage`, `ShiftSosCard`, `useFoLocationPublisher`; Lucien voice +
SOS audio recording via `utils/speech.ts`/`ShiftSosCard`), `camera` denied (photo capture uses
`<input type="file" capture="...">`, which goes through the OS camera app directly and never
touches `getUserMedia`, so it doesn't need the permission at all).

CORS (7.1.d) was already correct — origins come from `app.cors.allowed-origins` config, not
wildcarded, confirmed by reading `SecurityConfig.corsConfigurationSource()` directly.

**Real bug found and fixed along the way, not part of the task's literal checklist**: while
verifying HSTS would actually apply behind the real reverse-proxy chain (Caddy → nginx → backend),
disassembled `RemoteIpValve.class` and confirmed `server.tomcat.remoteip.internal-proxies`
(`TRUSTED_PROXY_CIDR`) is compiled with `java.util.regex.Pattern.compile(...)` — a **Java regex**,
not CIDR notation, despite the variable's name. SYSTEM 05's docker-compose network-pinning work
had documented the production value as the literal CIDR string `172.28.0.0/16`, which as a regex
would never match any real IP (verified with a standalone .NET-regex check reproducing the exact
behavior). Fixed everywhere this value was written: `docs/CONFIG-REFERENCE.md`,
`docs/INFRA-CURRENT.md`, `docs/SYSTEM-05-INFRASTRUCTURE.md`, `application.properties`'s own
comment, and a test fixture that modeled the same wrong shape — corrected to the regex
`172\.28\.\d{1,3}\.\d{1,3}`.

## TASK 7.2 — Encryption key rotation procedure [DONE — code + tests; live-instance verification deferred]

Read `LocalKeyEnvelopeEncryptor` and `KmsEnvelopeEncryptor` per 7.2.a. Found the real gap is
**Local-only**: KMS embeds key identity in its own ciphertext blob and resolves it automatically
on decrypt regardless of which key ARN is configured for new encrypts — inherently rotation-safe
already, no application-level change needed. `local` is what's actually configured in this
deployment (`docs/CONFIG-REFERENCE.md`), so it's the one that mattered.

- `EnvelopeEncryptor` interface: added `default boolean needsRewrite(String storedValue)` (false
  for Kms/Disabled) — the provider-agnostic hook the re-encryption job uses, so it never needs to
  know a specific provider's ciphertext format.
- `LocalKeyEnvelopeEncryptor`: rewritten to hold `Map<Integer, SecretKey>` + `currentVersion`.
  New format `enc:v1:kv<N>:<base64>` — the pre-existing `enc:v1:` envelope-format prefix is
  unchanged, `kv<N>:` is a new segment inside it. A stored value with no `kv<N>:` segment at all
  (everything encrypted before this task) is treated as key version 1, per 7.2.b's explicit
  instruction. An unconfigured/retired version fails the same row-scoped way wrong-key ciphertext
  already did (`[decryption failed]` placeholder, logged, not a crash).
- `EncryptionContext`: new `PII_ENCRYPTION_CURRENT_KEY_VERSION` (default `1`) and
  `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64` (`v:base64,v:base64,...`) properties.
  `PII_ENCRYPTION_KEY_BASE64` keeps its existing meaning (the *current* key) — no rename, no
  break for anyone already setting it. Fails fast if a version is listed in both places.
- New `PiiKeyRotationJob` (`server/src/main/java/com/recoverpro/server/scheduler/`): `@Scheduled`
  (01:30 daily) + `@SchedulerLock`, matching the other 14 schedulers' pattern. No-ops entirely
  when the active encryptor isn't `LocalKeyEnvelopeEncryptor`. Loops per-organization
  (`RlsOrgIdHolder.set(org.getId())` per org — the same cross-tenant pattern
  `LookupHashBackfillRunner` uses for the analogous HMAC-key backfill: a headless job that only
  ever holds one org's data in scope at a time, so `PlatformAdminAccessGuard`'s audited
  cross-org-read bypass doesn't apply). Hardcoded list of all 27
  `@Convert(EncryptedStringConverter.class)` columns across 9 tables (found via grep across every
  entity, not assumed) — raw `JdbcTemplate` per column, `SELECT ... WHERE col LIKE 'enc:v1:%' AND
  col NOT LIKE 'enc:v1:kv<currentVersion>:%' LIMIT 500`, `needsRewrite()` as the authoritative
  check, decrypt+encrypt+`UPDATE`. Deliberately batched/nightly, not a single full-table pass —
  documented in the class javadoc and the runbook why that's the intended design, not a shortcut.
- `docs/RUNBOOK-KEY-ROTATION.md` (7.2.d): full step-by-step rotation procedure, the verification
  query pattern for confirming a rotation has drained before retiring the old key, and what
  happens if the old key is retired too early (row-scoped failure, not a crash — matches the test
  below). Also updated `docs/RUNBOOK-SECRETS.md`'s stale note (previously said PII key rotation
  was unsafe "until SYSTEM 07 TASK 7.2 lands ciphertext versioning" — now it has, updated to point
  at the new runbook) and added the two new env vars to `docs/CONFIG-REFERENCE.md`.
- TASK 7.2.e (LookupHashService's HMAC key): documented as its own section in the same runbook,
  explicitly NOT attempted, per the task's own instruction. Key finding: unlike the AES key, a
  stored HMAC carries no version tag and can't have old-and-new-both-live the way ciphertext now
  can — rotating it breaks every blind-index lookup (duplicate-borrower detection)
  **instantly and completely**, not gradually, until `LookupHashBackfillRunner`'s synchronous,
  blocking, org-by-org recompute finishes. No rollback path once it's run (old hashes are
  overwritten row by row, no "previous hash" column).

New test: `LocalKeyEnvelopeEncryptorTest` (4 cases, real AES-GCM against real generated keys, not
mocked) — directly proves the task's own acceptance wording: a row encrypted under key v1 stays
readable after rotating to v2, new rows are written under v2; plus the legacy no-marker
backward-compat path and the retired-key-fails-closed-per-row (not per-app) behavior.

**Deferred, flagged rather than silently assumed**: a real rotate-and-redeploy cycle against a
live running instance and Postgres data. SYSTEM 05's OCI target has no live instance yet
(`docs/INFRA-CURRENT.md`), and further local `java -jar` boot cycles for this specific check were
explicitly paused mid-session (resource/time concerns, not a code-quality concern) in favor of the
already-solid unit-level proof above — the 654-test-suite-wide `mvn test` this session's other
changes were verified against already covers real Postgres integration tests elsewhere; this one
narrow scenario (a real restart with rotated config against real stored ciphertext) is the one
thing left genuinely unverified. Confirmed real material exists for this check whenever it's
picked up: 109 real `users` rows in the local dev DB, still in the pre-this-task legacy
`enc:v1:<base64>` format (no `kv1:` marker) — verified by direct query, not migrated as part of
this task.

## TASK 7.3 — Rate limit coverage audit [DONE]

Found the shared `RateLimiter` (Redis-backed, fail-open by design — "the primary brute-force
defence is BCrypt/Argon2id cost, not this counter") and `ChatRateLimiter` (fail-closed, since
Lucien calls cost real money/compute) already covering login, password reset request/OTP verify,
MFA verify, refresh token exchange, and contact form. Checked the task's full list against actual
code, not assumed complete:

- **File upload** (`FileUploadController.uploadFile`) and **report generation**
  (`ReportingController./generate`) had zero rate limiting — each triggers real background work
  (parsing/PII-encrypting a file; a DB aggregation + export job). Added `AppProperties.Security`
  fields (`fileUploadMaxAttempts`/`WindowMinutes`, `reportGenerateMaxAttempts`/`WindowMinutes`)
  and wired the existing `RateLimiter` into both, keyed by authenticated user id.
- **`/speak` and `/transcribe`** (Lucien's TTS/STT endpoints, `LucienController`) had zero rate
  limiting despite each calling an external voice microservice per request — exactly the "billing
  incident waiting to happen" the task warns about. Extended `ChatRateLimiter` with
  `checkAndRecordSpeak`/`checkAndRecordTranscribe` (fail-closed, matching chat's own reasoning).
- 7.3.d (spoofable rate-limit key): confirmed already fully closed via SYSTEM 08 TASK 8.1's
  `ClientIpResolver` (login/contact/refresh all use it). Found and fixed one small inconsistency:
  `RefreshTokenRateLimitFilter` had its own private `clientIp()` re-implementing
  `getRemoteAddr()` directly instead of delegating to the shared resolver — functionally
  identical today, but defeats the "single shared utility" point of TASK 8.1 if `ClientIpResolver`
  ever needs to get smarter. Now delegates.

## TASK 7.4 — Input validation sweep [DONE]

Task's own grep (`@RequestBody` without `@Valid`) started at ~23 real hits (2 were javadoc-comment
false positives in the webhook controllers, not real gaps).

- 13 typed-DTO cases: added `@Valid` plus real constraints where the DTO was previously
  unconstrained (`@Size` bounds on free-text fields, `@DecimalMin`/`@DecimalMax` geographic range
  checks on lat/lng fields already used for optional GPS metadata). Left fields optional exactly
  where the existing service-layer code already treated them as optional (e.g.
  `AttendanceCheckInRequest`'s GPS fields, `LogoutRequest.refreshToken`) — adding a new
  `@NotBlank`/`@NotNull` would have been a behavior change beyond this task's scope, not a pure
  validation-format fix.
- 8 of 10 raw `Map`/`Set` request bodies converted to real typed DTOs: `RemoveDispatchCaseRequest`,
  `ResolveIncidentRequest`, `LinkBorrowerRequest`, `RefundInvoiceRequest`,
  `GenerateGstLineItemRequest`, `GrantCompRequest`, `ChangePlanRequest` (shared by two
  controllers — same shape), `CheckoutRequest`. **Found and fixed one real bug along the way, not
  just a style gap**: `DailyDispatchController.removeCase()` called `.toString()` directly on
  `Map.get(...)` results with no null-checking — a missing JSON key threw an unhandled
  `NullPointerException` (a raw 500) instead of a clean 400. Now a `@NotNull`-constrained DTO
  field, same clean-400 path as everything else.
- 2 left as documented, deliberate exceptions, not silently missed: `RiskScoringController`'s
  `features` map is a genuinely open-ended ML feature bag (a fixed DTO would over-constrain it,
  and it's admin-only); `RoleController`'s `Set<UUID>` is already fully constrained by Jackson's
  own UUID parsing (empty set is valid — "remove all permissions" — so there's no missing
  not-empty check either).
- Confirmed `GlobalExceptionHandler` already handles both `MethodArgumentNotValidException` and
  `HttpMessageNotReadableException` cleanly (friendly field-level messages, no leaked
  class/field names) — no changes needed for 7.4.c.

## Verification

- `mvn -f server/pom.xml test`: 658 tests (654 + the 4 new `LocalKeyEnvelopeEncryptorTest` cases),
  0 failures, 0 errors, 2 skipped (the same pre-existing `StartupSchemaVersionCheckTest` skips
  every prior system this session has hit). Run repeatedly through TASK 7.1/7.3/7.4's changes,
  including catching and fixing several existing unit tests that constructed controllers directly
  (`PlatformSubscriptionControllerTest`, `SubscriptionControllerTest`) and needed updating for the
  new typed-DTO constructor parameters.
- Real boot + live curl (before the session paused further boots for TASK 7.2): confirmed
  `X-Content-Type-Options`, `X-Frame-Options`, `Content-Security-Policy-Report-Only`,
  `Referrer-Policy`, and `Permissions-Policy` all present on a real response, exactly matching
  TASK 7.1's acceptance check's header list (HSTS not observable over plain local HTTP, as
  expected — Spring Security only sends it when `request.isSecure()`, which requires a real TLS
  hop in front; not independently re-verified against a live reverse proxy).
- TASK 7.2's crypto behavior verified at the unit level (see above) but not against a live
  instance — flagged explicitly, not glossed over.
