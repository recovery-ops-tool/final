# Audit logging — design decisions

## Hash chain: removed, not implemented (TASK 10.1, 2026-08-18)

**Finding**: `UserActionAuditLog` declared `previousHash`, `rowHash`, and a `computeHash()`
method implying a cryptographic hash chain across audit rows. `computeHash()` had zero callers
anywhere in the codebase — confirmed by `grep -rn "computeHash|previousHash|rowHash"
server/src/main/java`, which returned hits only inside the entity itself. The columns were always
`NULL`. This is worse than having no hash chain at all: a schema that *looks* tamper-evident to
someone reading the entity or the database schema, but isn't actually computing or verifying
anything, is a false claim a customer security review or auditor could reasonably rely on and be
misled by.

**Decision: Option 1 (delete), not Option 2 (implement it for real).**

The real tamper-evidence control for this table already exists and already works:
`trg_audit_immutable` / `prevent_audit_log_update()`, a `BEFORE UPDATE OR DELETE` trigger created
in `V016__audit_log_partitioning.sql` (recreated by V028's later table rebuild), unconditionally
rejects both operations at the database level — confirmed applied to `user_action_audit_logs`
specifically by `AuditLogImmutabilityTest` actually running an UPDATE/DELETE against it and
catching the real exception, not just claimed to exist somewhere.

Correction (found while writing `AuditLogImmutabilityTest`'s third case): this table's original
V006 trigger, `trg_user_action_audit_immutable` / `fn_audit_log_immutable()`, was superseded by
V016's partitioning rebuild, which created a *different* function and trigger on this table with a
different exception message ("`%s rows are immutable`" vs. V006's "`audit log is immutable: %s on
%s is not permitted`"). V006's original function is still real and still active — it's what
protects `allocation_audit_logs` and `settlement_audit_logs` (via V084) — it's just not what
protects `user_action_audit_logs` anymore. Either way, a database trigger that makes tampering
*impossible* is a stronger and simpler guarantee than an application-computed hash chain that
nobody was verifying, and that would need a serializing lock on every insert to be race-safe if it
were ever wired up for real (see Option 2's cost, below) — a real throughput cost on a write-heavy
table, for a weaker guarantee than what already exists.

Option 2 (implementing the chain for real: compute `previousHash` from the prior row within the
org under a serializing lock, plus a verification endpoint) was not chosen. No customer contract
or compliance requirement calling for a *verifiable, walkable* hash chain (as opposed to
DB-level tamper-*prevention*, which the trigger already provides) has been identified. If one
surfaces later, this decision should be revisited — the trigger and a hash chain are not mutually
exclusive, but building the chain without a stated requirement was explicitly deprecated by the
tasklist itself ("Do not choose this without a stated requirement").

**What changed**: `UserActionAuditLog.previousHash`, `.rowHash`, and `.computeHash()` deleted from
the entity; `previous_hash`/`row_hash` columns dropped in `V095__drop_fake_audit_hash_chain.sql`.
`idx_audit_user_latest` (also added by the same original migration, on `user_id, created_at`) is
unrelated to the hash chain and was left untouched — it's a genuinely useful lookup index.

This does **not** affect `unified_audit_events` (the newer, actively-used audit table from
`V085__unified_audit_events.sql`) — that table never had a hash-chain concept and relies on its
own immutability trigger (the same `fn_audit_log_immutable()` function, reused).

## Partition maintenance extended to unified_audit_events (TASK 10.2, 2026-08-18)

`PartitionMaintenanceJob` (`scheduler/PartitionMaintenanceJob.java`) previously only rolled
`user_action_audit_logs`; `unified_audit_events` had explicit partitions only through 2026-12
(V085), so rows land in its `DEFAULT` partition after that with no automated follow-up. Refactored
to iterate a `PARTITIONED_TABLES` list (now both tables) and to create partitions
`LOOKAHEAD_MONTHS` (3) ahead rather than just next month, so a single missed run isn't fatal.
`@SchedulerLock` preserved unchanged. Covered by
`server/src/test/java/com/recoverpro/server/scheduler/PartitionMaintenanceJobTest.java`, which runs
the real job against the dev database and confirms partitions exist for both tables via
`to_regclass`, cleaning up only the partitions it created.

## unified_audit_events RLS: no bypass for NULL-org rows (found while writing TASK 10.1's test)

`rls_unified_audit_events_isolation` (V085)'s `USING` clause is
`organization_id = current_org_id() OR is_platform_admin` — unlike the
`OR current_org_id() IS NULL` pattern used elsewhere in this codebase for an unset session GUC,
there is **no** equivalent bypass here. A row with `organization_id IS NULL` (the documented
"true platform-global event" case, e.g. platform-admin actions with no tenant) is therefore
invisible to UPDATE/DELETE from an ordinary org-scoped session — matches 0 rows, no exception —
and only reachable through the platform-admin bypass. This doesn't weaken tamper-evidence (the
immutability trigger still blocks any UPDATE/DELETE that *does* reach a row), but it means
`AuditLogImmutabilityTest`'s `unified_audit_events` case needed a real organization and
`RlsOrgIdHolder.set(...)` to even reach the trigger — an org-less fixture silently no-op'd instead
of raising, which is what surfaced this. Not treated as a bug to fix here — the `WITH CHECK`
clause's `organization_id IS NULL` allowance for inserts appears deliberate (matches the entity's
own javadoc about platform-global events) — but worth knowing before anyone relies on being able
to manage a NULL-org row from a regular org session.

## Orphaned `audit_events` table: investigated, archived, dropped (TASK 10.5, 2026-08-19)

**Finding**: a pre-existing table literally named `audit_events` (distinct from
`unified_audit_events`) sat in the schema with no Flyway migration owning it and no live
application code referencing it (V085's own header comment first flagged this; re-confirmed by a
fresh grep this session). Columns: `id, action, actor_id, actor_role, after_data, before_data,
category, created_at, ip_address, metadata, occurred_at, organization_id, outcome, purpose,
request_id, retention_until, session_id, target_id, target_type, user_agent` — an unmistakable
early prototype of exactly what `unified_audit_events` now does properly (it even had a per-row
`retention_until`, a retention design TASK 10.3 is only now formalizing for the current table).
19 rows, all `category IN ('AUTH','FINANCIAL')`, real-looking data — genuine `LOGIN_SUCCESS`
events with real IPs/user-agents/request IDs and `organization_id`/`actor_id`/`target_id`
referencing real orgs/users, not placeholder rows.

**Provenance**: `occurred_at` for all 19 rows falls between 2026-05-16 and 2026-05-28. This
repository's own git history (`git log --reverse`) starts on 2026-07-17 with "Baseline: raw copy
of ops-tool server + recoverpro web" — seven weeks after this table's last row — and no commit in
`git log --all -S"audit_events"` ever created, migrated, or queried it; every hit is a later commit
*documenting* it as orphaned. Conclusion: the table and its data predate this repository entirely —
an artifact carried over in the raw database copy from the predecessor "ops-tool" project, from an
early auth-audit prototype abandoned before this codebase's history begins, superseded by the
properly Flyway-owned, RLS-protected, partitioned `unified_audit_events` (V085). Independently
corroborated: V058's own "close every remaining RLS gap" sweep (an earlier session, predates this
investigation) already looked at this exact table and skipped it as dead rather than adding RLS to
it — a second, earlier investigation reaching the same conclusion.

**Decision**: genuinely abandoned, provenance established with high confidence (not a guess) — per
TASK 10.5's own instruction this clears the bar for "archive and drop," not "leave and document
why it remains." All 19 rows archived verbatim (full column set) to
`docs/archive/audit_events_orphaned_table_archive.json` before the drop, so the historical
security data isn't discarded, just removed from the live, queryable schema. Table dropped by
`V106__drop_orphaned_audit_events_table.sql`. `RlsCoverageTest`'s allowlist entry for it removed
(the table no longer exists to allowlist).

## Auditor export path (TASK 10.4, 2026-08-19)

New `GET /api/v1/audit-events` (`AuditEventController`) — paginated, filterable read endpoint over
`unified_audit_events`: filters by date range (`from`/`to`), `actorUserId`, `action`,
`resourceType`, `severity`, `result`. `AuditEventSpecification` (mirrors the existing
`PtpSpecification` convention) builds the query; `AuditEventRepository` now extends
`JpaSpecificationExecutor`.

**Read scope (10.4.d)**: restricted to `Authz.ADMINS` (`ORG_ADMIN`/`PLATFORM_ADMIN`) — not
`MANAGERS_AND_ABOVE` — per the task's own recommendation, since audit rows can expose other users'
security-relevant actions across the org. V085 explicitly deferred this decision; this closes it.

**Org scoping (10.4.b)**: deliberately does *not* re-derive or app-filter organization scope for an
`ORG_ADMIN` caller — `rls_unified_audit_events_isolation` (V085) already restricts every query to
`current_org_id()`, and duplicating that in application code is exactly the kind of
control-duplication the task warned against (and that this session's SYSTEM 19 work found rotting
into a real bug elsewhere). A platform admin has no `current_org_id()` of their own, so they must
name a target org and a reason via `PlatformAdminAccessGuard.beginCrossOrgAccess` — same
reason-required, attributable elevation pattern already used by `ReportingController` and
`VisitSessionController` for equally sensitive cross-tenant reads, chosen over `PtpController`'s
unattended "span every org" variant because an audit trail of every tenant's security events is
higher-sensitivity than PTP records. Once elevated, the RLS bypass itself has no per-org
boundary — the controller adds an explicit `organizationId` predicate scoping the query back down
to the one org the admin named in their reason. That predicate is a UX/query filter layered on top
of an already-attributable elevation, not a second security boundary standing in for RLS.

**Export (10.4.c)**: `GET /api/v1/audit-events/export` streams CSV or JSON (`format=csv|json`,
default `csv`) over the same filters, reusing the existing `StreamingResponseBody` +
`csvJoin`/`csvEscape` pattern from `PtpController#exportCsv`. Every export writes its own
`AuditAction.AUDIT_LOG_EXPORTED` event (the enum value already existed, unused, since V085) naming
the exporter, the filters applied, and the row count.

**Web page (10.4.e)**: deferred to the separate frontend phase, per the same standing decision
already applied to SYSTEM 18's org-detail/pending-invites UI — none of TASK 10.4's own acceptance
criteria require it.

### Two real, pre-existing production bugs found while testing this against the real database

Both surfaced only because `AuditEventRlsIntegrationTest` exercises the real endpoint against real
Postgres RLS with a real, permission-bearing principal — a mocked query service (as in
`AuditEventControllerTest`) can't catch either one. Neither is caused by this task's own new code;
both were latent in `AuditServiceImpl`/`PlatformAdminAccessGuard` since V085/`c84c14f`, and this
task's own writes were the first thing to actually exercise the failing paths with a realistic
principal.

**Bug 1 — `actor_role VARCHAR(100)` overflowed for almost every real role, breaking the underlying
request, not just the audit row.** `AuditServiceImpl.joinRoles()` joined *every* granted authority
— role names AND every permission name each role carries (`UserPrincipal.buildAuthorities()`) —
into `actor_role`. That column's own javadoc says it's "a snapshot of the role name," and
permission names aren't `ROLE_`-prefixed in this schema's real seed data (e.g. `CASE_ASSIGN`), so
the unfiltered join massively overshot 100 chars for any role with a meaningful permission set —
measured against the real seeded `role_permissions`: FO 137 chars, TL 200, MANAGER 239, ORG_ADMIN
329, PLATFORM_ADMIN 456 (only CALLER/TRACER/the permission-less USER role stay under 100). Every
audited action by an FO/TL/MANAGER/ORG_ADMIN/PLATFORM_ADMIN principal — i.e. nearly all of them —
threw `DataException: value too long for type character varying(100)` on the INSERT. Since
`AuditServiceImpl.record()` runs in its own `REQUIRES_NEW` transaction and none of the ~20 call
sites catch its exceptions, that failure propagated all the way to a 500 response for whatever the
caller was actually trying to do (confirmed live: `PlatformAdminCrossOrgAuditTest`'s existing
`GET /api/v1/file-uploads` call was already returning 500 before this fix — the test only asserts
against `user_action_audit_logs`, a separate write that succeeds, so nothing had ever caught the
500 on the response itself). **Fixed two ways**: `joinRoles()` now filters to `ROLE_`-prefixed
authorities only (restores the field's actual documented contract, and incidentally fixes the
overflow for every realistic case on its own); `actor_role` widened to `VARCHAR(500)`
(`V108__unified_audit_events_actor_role_widen.sql`) as defensive headroom for a principal holding
several roles at once, which `User.roles` already permits as a `Set`.

**Bug 2 — `PlatformAdminAccessGuard.beginCrossOrgAccess`'s own audit write could never succeed for
a real cross-org target.** Its `unified_audit_events` row carries `organizationId = targetOrgId` (a
real, different org — that's the point, naming which tenant was accessed), but the class wrote that
row *before* calling `RlsOrgIdHolder.setBypass(true)`. `RlsAwareDataSource` stamps
`app.is_platform_admin` onto a connection only at checkout, read from `RlsOrgIdHolder.isBypass()`
at that instant, and `auditService.record()`'s own `REQUIRES_NEW` transaction is a fresh checkout —
so the bypass was never active for that specific INSERT. The row's `organization_id` matched
neither `current_org_id()` (platform admins have none) nor NULL (a real target org, not a
platform-global event), and the bypass clause was false too: every genuine cross-org access attempt
was rejected outright with "new row violates row-level security policy," for as long as this class
has existed — meaning the CROSS_ORG_ACCESS entry in `unified_audit_events` (the whole reason this
class's contract promises attributability) has never actually been written, ever, in production.
`beginUnattendedCrossOrgAccess` never hit this: it leaves `organizationId` NULL, which the
`WITH CHECK`'s NULL-org branch already allows unconditionally. **Fixed** by moving
`RlsOrgIdHolder.setBypass(true)` to before the `auditService.record(...)` call in
`beginCrossOrgAccess` — safe because nothing between there and the method's return can read another
tenant's actual business data, so activating the bypass one statement earlier creates no new window
of unlogged access; the class's real invariant ("no caller outside this class can reach
`setBypass` without going through the audit write first") is about call-graph topology, not literal
statement order inside this one trusted, unconditional method.

## Retention policy (TASK 10.3, 2026-08-19)

Signed off same session (7 years for `unified_audit_events`, 24 months for
`user_action_audit_logs`) — see `docs/AUDIT-RETENTION.md` for the full proposal, the sign-off
record, and implementation detail. Summary: `PartitionMaintenanceJob.purgeExpiredPartitions()`
drops whole monthly partitions (never `DELETE` — the immutability triggers would block it anyway)
past each table's horizon, dry-run by default via `AppProperties.Audit.retentionPurgeEnabled`,
logging every eligible partition either way. Row-count logging uses `pg_class.reltuples` rather
than `count(*)` — a real, found-live gap: `unified_audit_events` partitions carry FORCE RLS, and
this job's connection (a scheduled-job thread, no org context, no platform-admin bypass) would have
had every `count(*)` silently return 0.
