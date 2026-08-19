# PII encryption key rotation runbook

SYSTEM 07 TASK 7.2.d. Covers the AES-256 local encryption key
(`app.encryption.provider=local`, `PII_ENCRYPTION_KEY_BASE64`) — the provider actually configured
in this deployment (`docs/CONFIG-REFERENCE.md`). If `app.encryption.provider=kms` is ever used
instead, none of this applies: AWS KMS embeds key identity in its own ciphertext blob and resolves
it automatically on decrypt regardless of which key ARN is configured for new encrypts, so a KMS
key can simply be rotated in AWS/IAM directly, no application-level procedure needed.

## How local-key versioning works

Every encrypted value is stored as `enc:v1:kv<N>:<ciphertext>`, where `N` is the key version that
encrypted it (`enc:v1:` is the envelope *format* marker and hasn't changed; `kv<N>:` is new as of
this task). `LocalKeyEnvelopeEncryptor` can hold several key versions in memory at once — one
`currentVersion` (used for all new writes) plus any number of older ones (decrypt-only, kept just
long enough to still read rows nobody's re-encrypted yet). A value with no `kv<N>:` segment at all
predates this task entirely and is treated as key version 1 automatically — no data written before
this system was ever "unversioned forever," it's just implicitly v1.

Config surface (`server/src/main/resources/application.properties`,
`EncryptionContext.envelopeEncryptor()`):

- `PII_ENCRYPTION_KEY_BASE64` — unchanged meaning: the **current** key's raw material.
- `PII_ENCRYPTION_CURRENT_KEY_VERSION` (int, default `1`) — which version number the key above
  actually is.
- `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64` — retired keys still needed for decrypt-only, format
  `v:base64key,v:base64key,...` (e.g. `1:AbC123==,2:XyZ789==`). A version must appear in exactly
  one of this variable or `PII_ENCRYPTION_CURRENT_KEY_VERSION` — boot refuses to start if the
  same version shows up in both (`EncryptionContext` fails fast on this, see its class).

`PiiKeyRotationJob` (`server/src/main/java/com/recoverpro/server/scheduler/PiiKeyRotationJob.java`)
runs nightly (01:30, `@Scheduled` + `@SchedulerLock`) and walks every
`@Convert(EncryptedStringConverter.class)` column, re-encrypting any row still under a key version
other than current. It is the only thing that actually moves data forward to a new key — rotating
the config alone just means new writes use the new key and old rows stay readable under whichever
key they were already on.

## Rotation procedure

1. **Generate a new key.** 32 random bytes, base64-encoded:
   ```
   node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"
   ```
2. **Pick the new version number** — current version + 1. (First-ever rotation on a deployment
   that's never set `PII_ENCRYPTION_CURRENT_KEY_VERSION` explicitly: that deployment is implicitly
   on version 1, so the new one is version 2.)
3. **Update config, all in the same deploy:**
   - `PII_ENCRYPTION_KEY_BASE64` → the new key from step 1.
   - `PII_ENCRYPTION_CURRENT_KEY_VERSION` → the new version number.
   - `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64` → append `<old version>:<old key value>` (comma-separated
     if there were already other retired versions listed). The old key's raw material must be
     preserved here — if it's only in Secrets Manager under the old `PII_ENCRYPTION_KEY_BASE64`
     entry, copy it into a new `previous-keys` entry before overwriting that variable.
4. **Deploy.** New writes immediately use the new version. Every existing row stays exactly as
   readable as it was the moment before — nothing about existing data changes at deploy time.
5. **Watch `PiiKeyRotationJob`'s log lines** (`PiiKeyRotation: re-encrypted N value(s) to key
   version V across O organization(s) this run`) over the following nights. A large table can take
   several nightly runs to fully drain — this is expected (see the job's own class javadoc for
   why it's batched, not a single pass).
6. **Confirm completion with a per-column query** before retiring the old key. There's no single
   aggregate query across every encrypted column (the job's column list is hardcoded, not
   reflection-driven — see its class), so check each one that matters for the org(s) in question,
   e.g.:
   ```sql
   -- Replace <old_version> and repeat per table/column from PiiKeyRotationJob.ENCRYPTED_COLUMNS.
   SELECT count(*) FROM borrowers
   WHERE first_name LIKE 'enc:v1:%'
     AND first_name NOT LIKE 'enc:v1:kv<current_version>:%';
   ```
   A `0` for every column means every row under the old version has been migrated (or never
   existed for that org/table). Run this as a role that bypasses RLS (or repeat per
   `current_org_id()`), the same way the job itself does — a query scoped to one tenant's session
   context will only ever see that tenant's rows.
7. **Only once every column reports zero**, remove the old version's entry from
   `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64` and redeploy. Retiring it earlier orphans whatever the
   job hasn't reached yet: `decrypt()` returns `[decryption failed]` for those specific rows (logged,
   not a crash — see `LocalKeyEnvelopeEncryptorTest#retiredKeyVersionRemovedFromConfig_...` for
   exactly what that looks like), which is a real, avoidable data-loss-shaped incident, not a
   theoretical one.

## If a rotation needs to happen faster than the nightly job allows

The job's batch size (`PiiKeyRotationJob.BATCH_SIZE_PER_COLUMN`, currently 500 rows per column per
organization per run) and schedule (nightly) are tuned for "this is routine maintenance, not an
emergency." If a key is suspected compromised and old data needs to stop being decryptable under it
urgently:

- The old key can be removed from `PII_ENCRYPTION_PREVIOUS_KEYS_BASE64` before the job finishes —
  but understand this means rows the job hasn't reached yet become unreadable (`[decryption
  failed]`), not just "stop being decryptable by an attacker." This is a data-availability trade,
  not a clean win, and should be a deliberate decision, not a reflex.
- There's no supported way to force an immediate full-table pass today (the job doesn't expose a
  manual-trigger endpoint or a configurable "run now" flag) — building one, if this scenario is a
  real operational concern, is future work, not something this task built.

## Verified

`LocalKeyEnvelopeEncryptorTest` (4 tests, real AES-GCM crypto against real generated keys, not
mocked) directly covers this runbook's two claims: a row encrypted under key v1 stays readable
after rotating to v2, and new rows are written under v2. Also covers the legacy no-version-marker
backward-compat path, and a retired key's row-level (not app-level) failure mode.

**Not verified**: a real rotate-and-redeploy cycle against this deployment's actual running
instance and Postgres data (SYSTEM 05's OCI target has no live instance yet — see
`docs/INFRA-CURRENT.md`). The 109 real `users` rows in the local dev database are still in the
pre-this-task legacy format (`enc:v1:<base64>`, no `kv1:` marker) — confirmed by direct query, not
migrated as part of this task (no code path writes to them outside the app itself, and there's no
reason to touch real dev data just to demonstrate what the unit tests already prove). The first
real rotation on a real deployment should follow this runbook step by step and treat it as the
live verification this document doesn't yet have.

## Separate, harder problem: `PII_LOOKUP_HASH_KEY_BASE64` (SYSTEM 07 TASK 7.2.e)

**Not the same mechanism as above, and not solved by it.** `LookupHashService` computes an
HMAC-SHA256 blind index (phone/email/CKYC-ID duplicate-detection lookups) from
`PII_LOOKUP_HASH_KEY_BASE64`. Unlike the AES encryption key, there is no per-value key-version
tag on a hash — a stored HMAC value is just an opaque fixed-length string, indistinguishable from
one computed under any other key. **This means:**

- There is no way to keep *both* the old and new HMAC key "live" simultaneously the way
  `LocalKeyEnvelopeEncryptor` keeps multiple AES key versions loaded. The moment
  `PII_LOOKUP_HASH_KEY_BASE64` changes, every `WHERE phone_lookup_hash = ?` query computes its
  search hash under the *new* key, which will never match any row's hash still computed under the
  *old* one.
- **Rotating this key breaks every existing blind-index lookup instantly and completely** — not a
  gradual, background-job-drainable degradation like the AES case, an immediate full outage of
  duplicate-borrower detection — until every row's hash is recomputed.
- The recompute mechanism already exists: `LookupHashBackfillRunner`
  (`server/src/main/java/com/recoverpro/server/config/LookupHashBackfillRunner.java`), a one-time
  `CommandLineRunner` gated behind `LOOKUP_HASH_BACKFILL=true` (see its own javadoc — loops every
  org, recomputes `phoneLookupHash`/`emailLookupHash`/`ckycIdLookupHash` for every `Borrower`
  under whatever key is currently configured). It is **synchronous and blocking for the whole
  run**, not batched/background like `PiiKeyRotationJob` — there is no partial/graceful state
  where old and new hashes coexist while it runs.

**Procedure, if this key is ever rotated** (deliberately not attempted this task, per its own
explicit instruction):

1. Accept that duplicate-borrower detection (blind-index lookups) will be **fully non-functional**
   from the moment the new key is deployed until the backfill run completes — plan a maintenance
   window, don't do this live.
2. Generate a new key, deploy with `PII_LOOKUP_HASH_KEY_BASE64` set to it.
3. Run the app once with `LOOKUP_HASH_BACKFILL=true` (e.g.
   `mvn spring-boot:run -Dspring-boot.run.arguments=--app.backfill.lookup-hash=true`, or the
   equivalent one-off invocation for however the deployment actually runs the jar) — this recomputes
   every borrower's hashes under the new key, org by org.
4. Set `LOOKUP_HASH_BACKFILL=false` again before the next normal boot — leaving it `true` re-runs
   the full backfill on every startup.
5. There is no rollback path once this has run: old hashes are gone the moment they're
   overwritten, row by row, during the backfill (no versioning, no "previous hash" column). If the
   new key turns out to be wrong, the only way back is another full backfill with the old key.

This constraint is inherent to using a keyed HMAC as a searchable index rather than a design gap
this task could have closed — see `LookupHashService`'s own class-level reasoning for why a blind
index needs to be deterministic (same input + same key ⇒ same output, every time) in the first
place, which is exactly the property that makes it impossible to version the way encryption is.
