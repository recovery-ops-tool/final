# SYSTEM 10 — Audit Logging: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 10 block, 2026-08-18 session.
Continuation of the same session that completed SYSTEM 35, SYSTEM 18 TASK 18.1, SYSTEM 28
TASK 28.1, SYSTEM 26 TASK 26.1, SYSTEM 15 TASKs 15.1–15.2, and SYSTEM 08 TASK 8.1.

## Prerequisite check

SYSTEM 01 (multi-tenancy/RLS) — done, per the tasklist's own state and confirmed indirectly by
every RLS-dependent test in this session passing, including the new `unified_audit_events` case
below.

## TASK 10.1 — Resolve the fake hash chain [DONE]

Re-confirmed the finding exactly as stated: `grep -rn "computeHash|previousHash|rowHash"
server/src/main/java` returned hits only inside `entity/UserActionAuditLog.java`. No drift.

Chose **Option 1** (delete), as recommended — no customer contract or compliance requirement for
a verifiable walkable hash chain has been identified; the existing DB-level immutability trigger
is a stronger, simpler guarantee than an application-computed chain nobody was verifying.

- `UserActionAuditLog.previousHash`, `.rowHash`, `.computeHash()` deleted.
- `V095__drop_fake_audit_hash_chain.sql` drops `previous_hash`/`row_hash`; leaves
  `idx_audit_user_latest` (unrelated, genuinely useful) untouched.
- Decision and reasoning recorded in the entity's javadoc and in `docs/AUDIT-DESIGN.md`, per 10.1.c.

**Correction made mid-task**: the tasklist and this session's own first-draft documentation both
initially attributed `user_action_audit_logs`'s protection to `trg_user_action_audit_immutable` /
`fn_audit_log_immutable()` (V006). Actually running `AuditLogImmutabilityTest` against the real
database showed a *different* exception message than the one V006's function raises. Reading
V016 and V028 confirmed why: V016's partitioning rebuild replaced V006's original trigger on this
one table with a new function, `prevent_audit_log_update()`, and a new trigger,
`trg_audit_immutable` — recreated again by V028's later rebuild, still active today. V006's
original function is still real and still protects `allocation_audit_logs` /
`settlement_audit_logs` (V084) and `unified_audit_events` (V085) — just not this table. All three
places that had cited the wrong trigger (the entity javadoc, V095's migration comment, and
`docs/AUDIT-DESIGN.md`) were corrected to name V016/V028's actual trigger. This is exactly the
"stop and report drift" case the session's operating rule anticipates, except the drift was
between the tasklist and the database rather than between the tasklist and the application code —
corrected in place rather than treated as a blocker, since the underlying finding (fake hash
chain, real trigger) was still true, just attributed to the wrong migration.

**Test coverage**: `AuditLogImmutabilityTest` now covers all four append-only audit tables —
`allocation_audit_logs`, `settlement_audit_logs` (pre-existing), plus `user_action_audit_logs` and
`unified_audit_events` (added this session) — each proving INSERT succeeds and UPDATE/DELETE are
rejected by the real DB trigger, not by assumption.

While adding the `unified_audit_events` case, found (not a defect, but worth recording — see
`docs/AUDIT-DESIGN.md`): that table's RLS `USING` clause has no `current_org_id() IS NULL` bypass,
unlike the pattern used elsewhere in this codebase, so a row with a NULL `organization_id` (the
documented platform-global-event case) is invisible to UPDATE/DELETE from an ordinary org-scoped
session. Immutability is unaffected either way — the trigger blocks any write that does reach a
row — but the test needed a real org + `RlsOrgIdHolder.set(...)` to actually exercise the trigger,
which is what surfaced this.

**ACCEPTANCE** (tasklist's own wording: "no field or method in the codebase implies
tamper-evidence that is not actually enforced") — met: `computeHash()`/`previousHash`/`rowHash` no
longer exist anywhere; the only tamper-evidence claims left in the codebase are the four real DB
triggers, all now test-covered.

## TASK 10.2 — Extend partition maintenance to unified_audit_events [DONE]

Included in this session despite the user's own shorthand framing of SYSTEM 10 as "likely just a
deletion" — the tasklist itself marks TASK 10.2 as P0, same priority as 10.1, and this session's
established pattern is to complete every P0 task per system.

- `PartitionMaintenanceJob` refactored from a single hardcoded `user_action_audit_logs`-only method
  to iterate a `PARTITIONED_TABLES` list (`user_action_audit_logs`, `unified_audit_events`), per
  10.2.b/c.
- `@SchedulerLock` annotation preserved unchanged on the (renamed) scheduled method, per 10.2.d.
- Lookahead extended from "next month only" to `LOOKAHEAD_MONTHS = 3`, per 10.2.e.
- New `PartitionMaintenanceJobTest` runs the real job against the real dev database and asserts
  (via `to_regclass`) that partitions now exist for both tables across the lookahead window, per
  10.2.f. Cleans up only the partitions it created itself — never partitions that pre-existed
  (this must stay safe to run repeatedly against a persistent local database, not a disposable
  one).

**ACCEPTANCE** ("running the job creates next month's partition for both tables; the test
passes") — met: verified both by the test and by the manual `mvn test` run's own log output
showing partitions created for both `user_action_audit_logs_2026_09/10/11` and
`unified_audit_events_2026_09/10/11` (the latter a no-op since V085 pre-created those specific
months — expected, and unproblematic, since `CREATE TABLE IF NOT EXISTS` is idempotent by name).

## TASK 10.3 — Define and implement retention [DONE, 2026-08-19 session]

Wrote `docs/AUDIT-RETENTION.md` proposing 7 years for `unified_audit_events` (security/financial),
24 months for `user_action_audit_logs` (operational) — partition-drop is table-wide, not
per-category, so each table gets one horizon set by its most retention-sensitive rows, not its
least. Explicitly did not implement any purge logic until sign-off was recorded, per the task's own
instruction. User signed off on the proposal as written, same session.

Implemented into `PartitionMaintenanceJob` (same class as TASK 10.2): `purgeExpiredPartitions()`, a
second `@Scheduled` method under its own `@SchedulerLock`, dry-run by default
(`AppProperties.Audit.retentionPurgeEnabled`, default `false`), drops whole partitions past their
table's horizon via `pg_inherits`-discovered partition names (parsed against the same `<table>_yyyy_MM`
convention TASK 10.2 already creates them with), logs every eligible partition at INFO either way
with an approximate row count. Real bug found and fixed while building this: row-count via `SELECT
count(*)` would have silently returned 0 for every `unified_audit_events` drop, because those
partitions carry FORCE RLS (TASK 10.2) and this job's connection has no org context or
platform-admin bypass — switched to `pg_class.reltuples` (catalog metadata, not RLS-gated). New
tests in `PartitionMaintenanceJobTest`: dry-run drops nothing, enabled mode drops only
past-horizon partitions (including a real FORCE-RLS `unified_audit_events` partition), the DEFAULT
partition is never touched. Full detail: `docs/AUDIT-RETENTION.md`.

## TASK 10.4 — Auditor export path [DONE, 2026-08-19 session]

New `AuditEventController` at `GET /api/v1/audit-events` (paginated, filterable by date range,
actor, action, resource type, severity, result — `AuditEventSpecification`/`AuditEventRepository`
now `JpaSpecificationExecutor`) and `GET /api/v1/audit-events/export` (CSV/JSON, reuses
`PtpController`'s `StreamingResponseBody` + `csvJoin`/`csvEscape` pattern). Read scope restricted to
`Authz.ADMINS` (ORG_ADMIN/PLATFORM_ADMIN), closing V085's deferred MANAGER-scope decision. Org
scoping deliberately relies on RLS alone for an ORG_ADMIN (no app-level org predicate, avoiding a
control-duplication bug class this session found elsewhere); a platform admin must name a target
org and reason via the existing `PlatformAdminAccessGuard.beginCrossOrgAccess` pattern. Every export
writes its own `AuditAction.AUDIT_LOG_EXPORTED` event (existed unused since V085) naming the
exporter, filters, and row count — required a new `AuditResourceType.AUDIT_LOG` value (V107) since
none of the existing ones fit. Web page (10.4.e) deferred to the separate frontend phase, per this
session's standing decision — none of the task's acceptance criteria require it.

**Two real, pre-existing production bugs found and fixed** while testing this against the real
database (a mocked query-service test, `AuditEventControllerTest`, couldn't have caught either):

1. `actor_role VARCHAR(100)` overflowed for almost every real role (`AuditServiceImpl.joinRoles()`
   joined every granted authority, including permission names, not just role names — FO alone
   produced 137 chars, ORG_ADMIN 329, PLATFORM_ADMIN 456) — breaking the INSERT, and since nothing
   catches `AuditServiceImpl.record()`'s exceptions, breaking the caller's real request too
   (confirmed live: the pre-existing `PlatformAdminCrossOrgAuditTest` was silently returning 500 on
   `GET /api/v1/file-uploads`). Fixed: `joinRoles()` now filters to `ROLE_`-prefixed authorities
   only; column widened to `VARCHAR(500)` (V108) as headroom for multi-role principals.
2. `PlatformAdminAccessGuard.beginCrossOrgAccess`'s own `CROSS_ORG_ACCESS` audit write could never
   succeed for a real cross-org target — it wrote the row (`organizationId = targetOrgId`) *before*
   calling `RlsOrgIdHolder.setBypass(true)`, and `RlsAwareDataSource` stamps the RLS bypass GUC only
   at connection checkout, read from that flag at that instant; `auditService.record()`'s own
   `REQUIRES_NEW` transaction is a fresh checkout, so the bypass was never active for that specific
   INSERT. Every genuine cross-org access attempt has been rejected by RLS since this class existed
   — the attributability guarantee its own javadoc promises has never actually been recorded in
   `unified_audit_events` in production. Fixed by reordering the two lines (safe: nothing in between
   can read another tenant's business data either way).

Full detail, including both bugs' root-cause mechanics: `docs/AUDIT-DESIGN.md`.

## TASK 10.5 — Resolve the orphaned `audit_events` table [DONE, 2026-08-19 session]

Investigated provenance rather than guessing: 19 rows, real-looking `LOGIN_SUCCESS` events with
real IPs/user-agents/org and user references, `occurred_at` spanning 2026-05-16 to 2026-05-28 —
seven weeks before this repository's own git history begins (2026-07-17, "Baseline: raw copy of
ops-tool server + recoverpro web"), and no commit ever created, migrated, or queried it.
Conclusion: an abandoned early auth-audit prototype carried over from the predecessor project,
superseded by `unified_audit_events` (V085) before this codebase's history starts —
independently corroborated by V058's own earlier sweep already having skipped it as dead. All 19
rows archived verbatim to `docs/archive/audit_events_orphaned_table_archive.json`, table dropped
(`V106__drop_orphaned_audit_events_table.sql`), `RlsCoverageTest`'s now-stale allowlist entry for it
removed. Full detail: `docs/AUDIT-DESIGN.md`.

SYSTEM 10's rollup checkbox is now `[x]` — all 5 tasks done, acceptance criteria met (auditor-export
web page explicitly excepted per the standing frontend-phase deferral, which none of TASK 10.4's own
acceptance lines require).

## Verification

`mvn -f server/pom.xml clean test-compile` — clean.

SYSTEM 10's own specified command, `mvn -f server/pom.xml test -Dtest='*Audit*Test'` — re-run at
the end of the 2026-08-19 session (TASK 10.3/10.4/10.5 complete): 43/43 passed, across every
Audit-named test class in the codebase (immutability, the two production bugs' regression coverage,
denial-audit throttling, compliance audit, retention purge is covered separately under
`*Partition*Test` since it isn't Audit-named).

Full `mvn -f server/pom.xml clean test` — 785/785 passed, 2 skipped, 0 failures, 0 errors (end of
the 2026-08-19 session, after TASK 10.3/10.4/10.5 and both production-bug fixes).

Manual psql check specified by the tasklist ("attempt `UPDATE unified_audit_events SET reason='x'`
... confirm the trigger rejects it") — no psql client was available in this environment, so this
was done as an automated JDBC equivalent instead
(`AuditLogImmutabilityTest.unifiedAuditEvent_insertSucceeds_updateAndDeleteAreRejected`), which
exercises the identical UPDATE statement through the real driver and asserts the trigger's
exception — arguably stronger than a one-off manual session since it now runs on every future
test suite execution rather than being checked once and forgotten.
