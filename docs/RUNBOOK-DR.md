# Backup & Disaster Recovery Runbook

Owner system: `docs/PRODUCTION-TASKLIST.txt` SYSTEM 41 (LAYER 4 · P0). Last executed and verified:
2026-08-18.

**Read this first if you are here because something broke**: jump to
[Recovery procedures](#recovery-procedures). Everything before that section is background,
decisions, and the evidence behind them.

---

## TASK 41.1 — Current backup position (as found, 2026-08-18)

Investigated before changing anything, per the tasklist's own instruction. Findings:

- **No live production deployment exists yet.** `Caddyfile` still has the placeholder domain
  `yourdomain.com`; `~/.ssh/known_hosts` on the machine this audit ran from has no entry for any
  OCI host; nothing in the repo records a provisioned instance. `docs/INFRA-CURRENT.md` confirms
  OCI as the *target*, but SYSTEM 05's actual provisioning work has not run. This was confirmed
  with the repository owner directly before proceeding (2026-08-18), rather than assumed.
- **No backup mechanism exists anywhere** — dev or prod. `docker-compose.yml` (before this
  session) had no backup service, no WAL archiving, no volume-snapshot policy. A repo-wide search
  for `pg_dump`, `pg_basebackup`, `WAL-G`, `pgbackrest`, `barman` found nothing.
- **SSH key material found but unresolved.** `~/.ssh/recoverpro-server-key.pem` and
  `~/.ssh/ubuntu_deploy_rsa` exist on the local machine, but `known_hosts` shows no connection
  history to any host consistent with a current OCI deployment (only unrelated AWS-region IPs, a
  CTF wargame host, and LAN addresses). Provenance unconfirmed — flagged here rather than guessed
  at. **Open question for whoever runs SYSTEM 05**: confirm what these keys are for before reusing
  or discarding them.
- **RPO/RTO**: undefined before this session (see TASK 41.2 below).
- **PITR**: not possible before this session — no WAL archiving existed.
- **Encryption/access control on backups**: not applicable yet — no backups existed to encrypt or
  control access to.

This *is* the finding TASK 41.1 asks for: backup and restore posture was entirely unverified
because there was nothing to verify. The gap is now closed for the *mechanism* (below); it remains
open for *live execution against OCI* until SYSTEM 05 provisions a real instance.

---

## TASK 41.2 — RPO / RTO and backup configuration

### Targets (decision, not a default)

For a financial-recovery product handling customer collections data (encrypted PII, payment
records, audit trail with a 7-year-class retention expectation — see SYSTEM 39/40), the tasklist's
suggested starting position was adopted as the actual target:

| | Target | Mechanism |
|---|---|---|
| **RPO** | ≤ 5 minutes | Continuous WAL archiving, `archive_timeout=300` |
| **RTO** | A few hours (not yet measured at production scale — see caveat below) | Logical restore from nightly `pg_dump` + WAL replay |

This is a starting position for human sign-off, not a number handed down from nowhere — revisit
once real customer data volume and SLAs are known.

### What was built this session

All in `ops/backup/` (see `ops/backup/README.md` for install steps) plus two small app changes:

1. **`backup_agent` Postgres role** — `LOGIN BYPASSRLS`, granted `pg_read_all_data`. **Not** the
   app's runtime role (`opstool`). This is a hard requirement, not a style choice — see the TASK
   41.3 findings below for why `opstool` cannot take a full backup.
2. **`ops/backup/pg-backup.sh`** — nightly logical backup (`pg_dump -F c`) uploaded to OCI Object
   Storage via the AWS CLI (OCI's Object Storage exposes an S3-compatible API, so no OCI-specific
   tooling is needed), with retention pruning. **This exact mechanism was run live and verified**
   (TASK 41.3).
3. **`ops/backup/pg-wal-archive.sh`** + `docker-compose.yml` changes — continuous WAL archiving
   (`archive_mode=on`, `archive_timeout=300`) to the same bucket, for the ≤5-minute RPO. **Not yet
   exercised against a live Postgres instance** — this repo has no Docker/live-server environment
   available this session. Verify during SYSTEM 05 provisioning before relying on it.
4. **`ops/backup/postgres.Dockerfile`** — the `postgres` compose service now builds a small image
   adding the AWS CLI (needed for #2/#3). Deliberately moved from `postgres:16-alpine` to
   `postgres:16` (Debian/glibc) because the AWS CLI needs glibc — alpine uses musl. Flagged clearly
   in the Dockerfile and `docker-compose.yml` comments: **do not swap Postgres base images on a
   database that already has data** without checking libc-collated text indexes first (musl and
   glibc order text differently; this is safe now only because nothing is deployed yet).
5. **`ops/backup/recoverpro-backup.{service,timer}`** — systemd timer running the nightly backup
   at 02:00 UTC via `docker compose exec`. Not yet installed anywhere real.
6. **`ops/backup/restore-test.sh`** — scripts the exact restore-and-verify procedure run manually
   below, for the quarterly re-run (TASK 41.3.e).
7. **`server/.../config/S3Config.java` + `application.properties`** — added
   `aws.s3.endpoint-override` (env `AWS_S3_ENDPOINT_OVERRIDE`), empty by default (unchanged
   behavior: real AWS S3). See TASK 41.2.b below for why.

### 41.2.b — Object storage (file uploads/reports)

`docker-compose.yml`'s `uploads` and `reports` volumes are local to the single app VM, with
**zero** redundancy or backup — exactly the "forgotten in DR planning" gap the tasklist warned
about. Rather than build a second, parallel backup pipeline for these volumes, the fix is to stop
storing them only on local disk: the app already has a complete, tested S3-compatible storage path
(`S3OrLocalStoragePort`, gated by `AWS_S3_ENABLED`) that just talks to real AWS today. This session
added `aws.s3.endpoint-override` so the same code path can point at OCI Object Storage instead —
durable and versionable by the object store itself, no bespoke backup script needed. **Not yet
turned on** (`AWS_S3_ENABLED` still defaults to `false`) — enabling it is a SYSTEM 05/deploy-time
decision once a real bucket exists, tracked as an open item below.

### 41.2.c — Redis durability review

Every Redis consumer in the codebase was enumerated (`grep -rl "RedisTemplate\|@Cacheable" `). All
fall into one of two buckets:

- **Reconstructible**: `DashboardCacheService`, `FeatureFlagService` cache, ShedLock locks — pure
  caches or coordination state, correct-by-recompute after loss.
- **Bounded, retry-safe transient state**: `ConfirmationService`/`IdempotencyKeyService` tokens,
  `RateLimiter`/`ChatRateLimiter` counters, `SseTicketService`, `RefreshTokenRateLimitFilter` — all
  short-TTL by design; losing them forces a retry, not data loss. Also found and checked
  `RedisFileStorageServiceImpl` (a 24-hour-TTL staging buffer for in-flight file uploads, not the
  durable upload record) — same category: a Redis outage mid-upload forces the user to retry
  within the processing window, but no committed data is Redis-only.

**Conclusion**: nothing durable lives only in Redis. No Redis backup is required. The `redisdata`
docker volume plus Redis's default RDB snapshotting is sufficient for warm-restart continuity, not
disaster recovery, and that's an acceptable gap given the above.

### Open items (require SYSTEM 05 or a human decision)

- Confirm the mystery SSH keys' provenance before either using or deleting them.
- Provision the OCI instance, then execute the one-time setup in `ops/backup/README.md` and
  actually exercise WAL archiving + the systemd timer for the first time against something real.
- Decide whether/when to turn on `AWS_S3_ENABLED` + point `AWS_S3_ENDPOINT_OVERRIDE` at OCI Object
  Storage for `uploads`/`reports`.
- Re-run the restore test (TASK 41.3 below) against production-scale data once it exists — the
  timings recorded here are a mechanism proof on a small dataset, not an RTO guarantee.

---

## TASK 41.3 — Restore test: real results (2026-08-18)

No live production exists (see TASK 41.1), so this test ran against the local development
Postgres 16.11 instance — the same major version targeted for OCI, with a real, fully-migrated
schema (Flyway V001–V095, 121 tables, 57 RLS-forced tables, 27 immutability triggers) and real
(locally-seeded) data. This is a genuine restore test of the *mechanism* — pg_dump/pg_restore, role
requirements, RLS/trigger/encryption survival — not a tabletop exercise. It is **not** a test of
OCI-specific infrastructure, which doesn't exist yet, and **not** a measurement of RTO at
production data volume (2.5MB / 121 tables here vs. whatever production actually holds).

### What went wrong on the first attempt (the actual point of this exercise)

1. **`pg_dump` as the app's own role (`opstool`) fails outright.** `FORCE ROW LEVEL SECURITY` (set
   on all 57 RLS-protected tables, correctly, since SYSTEM 01) blocks even the table owner from
   reading across orgs — `pg_dump` errored immediately on the first RLS-forced table:
   ```
   pg_dump: error: query failed: ERROR:  query would be affected by row-level security policy
   for table "agent_capacity_config"
   ```
   **Fix**: a dedicated `backup_agent` role, `LOGIN BYPASSRLS`, distinct from `opstool`. This is
   the right shape for defense in depth, not just a workaround — the app's runtime credential
   stays RLS-bound even if compromised; only the narrowly-scoped backup process can see across
   orgs, and it never runs application code.
2. **`pg_restore` as a non-superuser role fails on extension-owned objects.** Restoring with
   `--role=opstool` (to preserve ownership without granting superuser) hit:
   ```
   ERROR:  permission denied to create extension "vector"
   HINT:  Must be superuser to create this extension.
   ```
   pgvector isn't marked "trusted" in this Postgres build, so only a superuser can install it —
   this cascaded into 87 downstream errors (every table/index/FK depending on the `vector` type or
   on an RLS-forced FK-validation query under the non-bypass role).
   **Fix**: restore as `postgres` (superuser) directly, not through `--role`. This is standard and
   correct: the restore *process* legitimately needs superuser (to recreate extensions, roles, and
   bypass RLS for FK validation) even though the *running application* must never have it.
   Ownership of the restored objects is preserved correctly regardless (pg_dump's embedded `ALTER
   ... OWNER TO opstool` commands run fine under superuser).

### The clean run

| Step | Result |
|---|---|
| Backup (`pg_dump -F c`, as `backup_agent`) | **1 second**, 2,584,873 bytes, zero errors |
| Restore (`pg_restore -j4`, as `postgres`) | **3 seconds**, zero errors, all 121 tables |
| Table count, source vs. restored | 121 vs. 121 — match |
| RLS-forced tables, source vs. restored | 57 vs. 57 — match |
| RLS policies, source vs. restored | 57 vs. 57 — match |
| Immutability triggers, source vs. restored | 27 vs. 27 — match |
| Key-table row counts (`allocations`, `organizations`, `users`, `unified_audit_events`) | identical in both (1894 / 75 / 52 / 48) |

### Functional verification (not just "the policy exists")

**RLS enforcement**, connected as `opstool` (the app's real role) against the *restored* database,
with `app.current_org_id` set per query:

| Org GUC | `allocations` rows visible |
|---|---|
| Org A (first-created org) | 0 |
| Org B | 1,849 |
| Random unrelated UUID | 0 |

Confirms RLS isn't just present in the schema after restore — it actively enforces tenant
isolation exactly as before.

**Immutability trigger**, attempted directly as the `postgres` superuser (the strongest possible
adversary for this test) against a known row in the restored `unified_audit_events`:

```
UPDATE unified_audit_events SET action = 'TAMPERED' WHERE id = '...';
ERROR:  audit log is immutable: UPDATE on unified_audit_events_2026_08 is not permitted
DELETE FROM unified_audit_events WHERE id = '...';
ERROR:  audit log is immutable: DELETE on unified_audit_events_2026_08 is not permitted
```

Both rejected — the trigger survived the restore and blocks tampering even for a superuser.

**Encrypted PII decryptability**: a standalone verification tool
(`server/src/test/java/.../security/encryption/DrRestoreDecryptCheck.java` — deliberately *not*
named `*Test.java`, so `mvn test`/CI never runs it; it's a manual DR tool, invoked by
`ops/backup/restore-test.sh`) connected to the restored database and decrypted every encrypted
column on the `users` table using the deployment's `PII_ENCRYPTION_KEY_BASE64`:

```
columns checked        = 156
successfully decrypted = 86
decryption FAILED      = 0
null/unencrypted       = 70
RESULT: PASS
```

Confirms the encryption key was available and correct in the restore environment, and that
ciphertext written before the backup is still readable after — the exact thing TASK 41.3.c warns
a "perfect backup with an unavailable key" would fail.

The tool never logs decrypted plaintext, only pass/fail counts, so it's safe to run against real
customer data without putting PII in a terminal transcript or log file.

### Caveats — read before trusting this for a real incident

- **Scale**: 2.5MB, 121 tables, ~2,000 total rows. Production data will be orders of magnitude
  larger; both backup and restore time, and any RPO/RTO claim, must be re-measured once real
  volume exists. Treat the "1s / 3s" numbers as proof the mechanism works, not as an RTO estimate.
- **Not OCI**: this ran on local Windows Postgres 16.11, not the (not-yet-provisioned) OCI
  instance. Object-storage upload/download time for WAL and dumps, and OCI-specific I/O
  characteristics, are entirely unmeasured.
- **WAL/PITR untested**: only the logical `pg_dump`/`pg_restore` path was exercised. Continuous WAL
  archiving (`pg-wal-archive.sh`) has never run against a live instance — see TASK 41.2 item 3.

### Next scheduled run

Quarterly, per TASK 41.3.e, via `ops/backup/restore-test.sh`. **First priority**: re-run this
whole test against the real OCI instance as soon as SYSTEM 05 provisions it — that run is the one
that actually validates production DR readiness; this one validates the mechanism.

| Date | Target | Result | Notes |
|---|---|---|---|
| 2026-08-18 | Local dev Postgres (stand-in, no OCI instance exists) | PASS (after fixing the two role-privilege issues above) | See full detail above. Next run: against OCI once provisioned. |

---

## TASK 41.4 — DR runbook

### Decision authority

Until SYSTEM 42 (Admin & Platform Support) formally assigns an on-call/incident-commander role,
the repository owner is the decision-maker for every scenario below. **Open item**: assign and
document a real on-call rotation once there is a team large enough to need one.

### Detection

- Automated: none yet — SYSTEM 12 (Monitoring & Observability) and SYSTEM 13 (Error Tracking) own
  alerting and haven't run. Until then, detection is manual (a user report, or a maintainer
  noticing).
- Once SYSTEM 12 lands: readiness-probe failures (SYSTEM 05 TASK 5.2), Hikari pool exhaustion
  (SYSTEM 02 TASK 2.3), and elevated error rates should page automatically. Cross-reference here
  when that's wired up.

### Communication plan

- **Internal**: decision-maker above notifies anyone else with production access immediately on
  declaring an incident.
- **Customer-facing**: not yet defined — there's no status page, support-email template, or
  customer-comms process in the repo. **Open item**, tracked here rather than invented: draft one
  before the first real customer depends on this product. At minimum, any incident touching
  customer data (partial corruption, accidental deletion) should be disclosed, not silently fixed.

### Recovery procedures

#### Scenario: total database loss (instance destroyed, disk failure, etc.)

1. Declare the incident; note the time.
2. Provision a replacement Postgres instance (OCI Ampere A1, per `docs/INFRA-CURRENT.md`).
3. Restore the most recent base backup + replay WAL up to the last available point using the
   procedure in `ops/backup/restore-test.sh` (run for real, not into a throwaway database this
   time) — **as the `postgres` superuser**, per the TASK 41.3 findings above; do not restore as
   the app role.
4. Run the verification block from TASK 41.3 (RLS forced-table count, policy count, trigger count,
   a live cross-org RLS check, an immutability-trigger tamper attempt, and
   `DrRestoreDecryptCheck`) against the *actual* restored instance before pointing the app at it.
   A restore that "completes" without these checks is not verified.
5. Point `DB_URL` at the new instance, redeploy, confirm `/actuator/health/readiness` is green.
6. Record actual elapsed time vs. the RTO target and file a follow-up if it exceeded 2× target.

#### Scenario: one corrupted table

1. Identify the table and the approximate corruption time from the audit trail
   (`unified_audit_events` — this is exactly why SYSTEM 10's audit work matters for DR).
2. Prefer PITR to just before the corruption over a full restore: restore into an isolated
   database up to that point in time, then `pg_dump --table=<name>` just that table and
   `pg_restore` it into production inside a transaction, after confirming row counts and a sample
   of rows look correct in the isolated copy first.
3. If the corruption is in an immutable audit table, the trigger already prevented in-place
   corruption by definition — this scenario shouldn't be reachable for those tables. If it somehow
   is (e.g. a bug in the trigger itself), treat it as a security incident, not a routine restore.

#### Scenario: accidental mass delete

1. **Do not** run any write against the affected table until the time of the delete is known —
   check `unified_audit_events` for the responsible action first (this is the audit trail's whole
   reason for existing per `docs/AUDIT-DESIGN.md`).
2. PITR to immediately before that timestamp, into an isolated database.
3. Diff the isolated copy against current production for the affected table(s); re-insert only the
   missing rows (an `INSERT ... SELECT` from the isolated copy, scoped to the affected org/table),
   rather than a full-database rollback — a full rollback discards every legitimate write that
   happened after the accident too.
4. Audit the recovery action itself.

#### Scenario: bad migration in production

1. Do not run `flyway:undo` — this codebase has no down-migrations by policy (SYSTEM 03).
2. If the migration is purely additive and broke nothing at the data level, ship a forward-fixing
   migration instead of restoring anything.
3. If it corrupted data, treat as "accidental mass delete" or "corrupted table" above, scoped to
   whatever the bad migration touched, using PITR to immediately before it ran.

### Validation checklist (after any recovery)

- [ ] RLS forced-table count and policy count match the pre-incident baseline.
- [ ] All four immutability triggers reject a test UPDATE/DELETE.
- [ ] `DrRestoreDecryptCheck` passes against the recovered database.
- [ ] Application boots, `/actuator/health/readiness` is green.
- [ ] Spot-check row counts on the tables involved in the incident against the last known-good
      backup.
- [ ] Incident recorded: timeline, root cause, data affected, customers affected (if any),
      follow-up items.

---

## Rollup

SYSTEM 41 acceptance per the tasklist: *"A completed, timed restore test recorded in
docs/RUNBOOK-DR.md."* — met above (TASK 41.3), with the explicit caveat that it validates the
mechanism against a local stand-in, not the (not-yet-provisioned) OCI production instance. Treat
SYSTEM 41 as **mechanism-complete, production-unverified** until the first real OCI restore test
runs and its result is appended to the table in TASK 41.3.
