# Secrets runbook

SYSTEM 04 TASK 4.3.c. Covers: how AWS Secrets Manager mode works, and the rotation procedure for
each secret it can source. Full inventory of every config variable (not just secrets) is
`docs/CONFIG-REFERENCE.md`.

## How AWS Secrets Manager mode works

Off by default (`AWS_SECRETS_MANAGER_ENABLED` unset/`false`) — every secret comes from its env var
exactly as before, and nothing here changes local dev or an existing deployment.

When `AWS_SECRETS_MANAGER_ENABLED=true`, `AwsSecretsManagerEnvironmentPostProcessor`
(`server/src/main/java/com/recoverpro/server/config/AwsSecretsManagerEnvironmentPostProcessor.java`)
runs once at boot, before any other config is resolved, and looks up each of the following secret
names in AWS Secrets Manager under `<AWS_SECRETS_MANAGER_PREFIX><VAR_NAME>` (default prefix
`recoverpro/`, e.g. `recoverpro/JWT_SECRET`):

- `DB_PASSWORD`
- `JWT_SECRET`
- `PII_ENCRYPTION_KEY_BASE64`
- `PII_LOOKUP_HASH_KEY_BASE64`
- `STRIPE_SECRET_KEY`
- `STRIPE_WEBHOOK_SECRET`
- `RAZORPAY_KEY_SECRET`
- `RAZORPAY_WEBHOOK_SECRET`
- `MAIL_PASSWORD`
- `REDIS_PASSWORD`

Any of these found in Secrets Manager overrides its env var for that boot. Any not found (not
migrated yet) silently falls back to its env var — migrate one variable at a time, there's no
all-or-nothing cutover. `AWS_SECRETS_MANAGER_REGION` (falls back to `AWS_REGION`) sets the AWS
region to query.

**Not included**, deliberately: `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` (needed to authenticate
*to* Secrets Manager — fetching them *from* Secrets Manager is circular), `PII_ENCRYPTION_KMS_KEY_ID`
(an ARN/identifier, not secret material), and anything not in `docs/CONFIG-REFERENCE.md`'s secret
rows (Stripe/Razorpay publishable keys, plan/price ids, GSTIN, etc. — none of those are secrets).

**Credentials**: this app runs on OCI (`docs/INFRA-CURRENT.md`), not AWS compute, so there is no IAM
instance role to assume. `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` remain a required bootstrap
credential pair in the environment even with this mode on — reaching an AWS-hosted secret store
from non-AWS compute has no way around that. What this mode actually buys: **one** credential pair
in the environment instead of every individual secret in the list above. If compute ever does move
onto AWS, this same code picks up an IAM role automatically with no changes (it uses the AWS SDK's
default credential provider chain, not static credentials — see the class's javadoc).

**Failure mode**: if `AWS_SECRETS_MANAGER_ENABLED=true` and Secrets Manager can't be reached at all
(bad credentials, wrong region, network partition), boot refuses to start with a clear error —
verified for real, see "Verification" below. A specific secret simply not existing yet in Secrets
Manager is not this case; that one falls back to its env var as designed.

**No hot-reload**: secrets are fetched once, at boot. Rotating a value in Secrets Manager has no
effect on an already-running instance — every rotation below ends with "then redeploy/restart the
app," not "then it takes effect automatically." Building live secret refresh (e.g. via a
`@RefreshScope`-style mechanism) was not in TASK 4.3's scope and is not implemented.

## Rotation procedure — most secrets

Applies to: `DB_PASSWORD`, `MAIL_PASSWORD`, `REDIS_PASSWORD`, `STRIPE_SECRET_KEY`,
`RAZORPAY_KEY_SECRET`. These have no cross-request state tied to the old value — the new value just
needs to reach the app.

1. Rotate the credential at its source first (Postgres role password, Redis AUTH password, Gmail
   app password, Stripe/Razorpay dashboard) — never invalidate the old value before the new one is
   confirmed working.
2. Write the new value to `recoverpro/<VAR_NAME>` in Secrets Manager (or the env var, if Secrets
   Manager mode isn't in use).
3. Redeploy/restart every instance so the new value is picked up (see "No hot-reload" above).
4. Confirm the app boots and the relevant feature works (DB connectivity, mail send, Redis-backed
   session/cache, a real Stripe/Razorpay test call).
5. Only then invalidate the old credential at its source.

## Rotation procedure — webhook secrets

Applies to: `STRIPE_WEBHOOK_SECRET`, `RAZORPAY_WEBHOOK_SECRET`.

Same as above, but the new value must be **generated at the provider's dashboard first** (Stripe:
Developers → Webhooks → the endpoint → roll secret; Razorpay: Settings → Webhooks → the webhook →
regenerate secret) — these aren't values you choose, they're issued by the provider alongside the
webhook endpoint registration. `WebhookSecretsStartupCheck`
(`server/src/main/java/com/recoverpro/server/config/WebhookSecretsStartupCheck.java`) refuses to
boot if the corresponding API key is set but the webhook secret is blank, so a mismatch here is
caught at the next restart, not silently.

## Rotation procedure — `JWT_SECRET`

**Not graceful.** `JwtTokenProvider` holds exactly one signing key at a time — there is no key-id
versioning for JWTs the way there is for PII encryption (see below). Rotating this immediately
invalidates every currently-issued access and refresh token: every logged-in user is signed out and
must log in again. There is no way to rotate this without that consequence today.

1. Generate a new secret: `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`.
2. Write it to `recoverpro/JWT_SECRET` (or the env var).
3. Redeploy during a maintenance window, or accept that every active session ends at redeploy —
   plan the timing accordingly (this is very different from the other rotations above, which are
   invisible to end users).

## Rotation procedure — `PII_ENCRYPTION_KEY_BASE64` / `PII_LOOKUP_HASH_KEY_BASE64`

**`PII_ENCRYPTION_KEY_BASE64` can now be rotated safely** — SYSTEM 07 TASK 7.2 landed ciphertext
key-versioning (`EncryptionContext`/`LocalKeyEnvelopeEncryptor` hold multiple key versions at
once, a background `PiiKeyRotationJob` migrates old rows forward). The full procedure —
including the two new env vars this introduced (`PII_ENCRYPTION_CURRENT_KEY_VERSION`,
`PII_ENCRYPTION_PREVIOUS_KEYS_BASE64`) and how to confirm a rotation has fully drained before
retiring the old key — is `docs/RUNBOOK-KEY-ROTATION.md`. That runbook is the authority for this
key; this file's earlier steps (rotate at source, write the new value, redeploy, confirm, then
invalidate the old value) don't directly apply to it the same way they do to
`DB_PASSWORD`/`MAIL_PASSWORD`/etc. above — a key rotation here specifically needs the
old key kept *available* (not invalidated) until the background job finishes, which is the whole
point of `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64`.

**`PII_LOOKUP_HASH_KEY_BASE64` is still the harder, unsolved-by-the-above problem** —
SYSTEM 07 TASK 7.2.e deliberately did not attempt it (documented, not just skipped). Unlike the
encryption key, a stored HMAC value carries no version tag and can't have "old and new both live"
the way ciphertext now can — rotating it invalidates every existing blind-index lookup
(phone/email/CKYC duplicate detection) **instantly and completely**, not gradually, until
`LookupHashBackfillRunner` recomputes every row (`LOOKUP_HASH_BACKFILL=true`, one synchronous
blocking run, then back to `false`). Full constraint and procedure:
`docs/RUNBOOK-KEY-ROTATION.md`'s "Separate, harder problem" section.

Both variables can be *sourced* from Secrets Manager as before; only the rotation procedure for
the encryption key has changed (it's now safe, following the dedicated runbook), not how Secrets
Manager mode fetches it.

## Verification

- `AwsSecretsManagerEnvironmentPostProcessorTest` (mocked `SecretsManagerClient` — no local AWS
  Secrets Manager emulator exists in this repo): secret found → injected and visible under its var
  name; secret not found → falls through to the env var untouched; Secrets Manager unreachable
  (`SdkClientException`) or an AWS-side error (`SecretsManagerException`) → refuses to start.
- Real boot, `AWS_SECRETS_MANAGER_ENABLED=true` with no AWS credentials in the environment (`java
  -jar target/recoverpro-server-1.0.0.jar`, `SPRING_PROFILES_ACTIVE=prod`): refused to start with
  `AWS_SECRETS_MANAGER_ENABLED=true but Secrets Manager could not be reached ... Refusing to
  start`, non-zero exit — confirmed for real, not just unit-tested.
- **Not verified**: an actual successful fetch against a real, provisioned AWS Secrets Manager
  instance with real secrets populated. No such instance exists yet (no live AWS/OCI deployment —
  see `docs/INFRA-CURRENT.md`, SYSTEM 05 pending). The fetch-success path is covered by the mocked
  unit test only; re-verify against the real thing the first time this mode is actually turned on
  in a real environment.
