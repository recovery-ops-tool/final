# SYSTEM 01 — Multi-Tenancy (RLS): execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 01 block, 2026-08-18 session (continuation of
the same session that completed SYSTEM 41, and audited-but-did-not-complete SYSTEMs 08/10/15/20/26/
28/35 in an earlier session).

## Prerequisite check

None — this is the foundation system, no prerequisites.

## TASK 1.1 — Automated RLS coverage guard [DONE]

`server/src/test/java/com/recoverpro/server/security/RlsCoverageTest.java`, following the exact
catalog query from the task spec, against the real local dev database (same pattern as every other
`AbstractIntegrationTest` subclass in this codebase — no Testcontainers wiring exists here to
reuse).

**Investigation before writing the allowlist** (per the task's own "an unexplained entry is a bug"
instruction): the raw catalog query returned 8 non-partition tables with an `organization_id`/
`org_id` column and no RLS. Each was individually verified, not assumed:

- `audit_events`, `friday_chat_messages`, `friday_chat_sessions`, `notifications`, `rag_chunks`,
  `rag_documents_orgscoped_orphan_backup` — all confirmed dead (zero rows, no entity mapping, no
  live repository/service reference) via `V058__rls_remaining_org_scoped_tables.sql`'s own header
  comment, re-confirmed against current code by grepping for `@Table(name = "...")` — each is
  superseded by a correctly-RLS'd replacement (`lucien_chat_*`, `app_notifications`,
  `rag_document_chunks`) or is genuinely orphaned schema history.
- `receipt_sequences` — deliberately not RLS'd; only ever accessed via a keyed
  `INSERT ... ON CONFLICT` upsert with an explicit `organization_id`, no listing surface exists to
  protect (V058's own rationale, re-confirmed).
- `users` — deliberately not RLS'd, and the highest-risk entry in the allowlist. Unlike every other
  exemption this is live, sensitive, org-scoped PII with **zero DB-layer tenant isolation** — the
  reason is structural (`findByEmail` is the pre-authentication login lookup, called before any org
  context exists; RLS would break login itself), not an oversight, but it means `users` isolation
  depends entirely on every application-layer query being correctly org-scoped, with no backstop.
  Documented prominently in the test's own allowlist comment rather than silently accepted — this
  is SYSTEM 09's (RBAC) territory to actually harden, not something this task fixes.

A companion test, `allowlistedTables_stillExistAndHaveNoRls`, guards the allowlist itself from
rotting silently (an allowlisted table that later gains a real policy, or gets dropped, should
force a re-check, not be silently skipped forever).

**Verified the failure mode works, not just the pass**: temporarily created a scratch table with an
`organization_id` column and no policy, confirmed `RlsCoverageTest` fails naming it, then dropped
it — matches the task's exact acceptance wording.

**Table-count note**: the partition CHILD tables of `unified_audit_events` (see TASK 1.2) also
carry `organization_id` and are caught by this same query. They pass without any allowlist entry
because TASK 1.2's fix (below) gives every partition real, forced RLS with a policy — not because
they're exempted.

## TASK 1.2 — Verify partitioned-table policy inheritance [DONE — real gap found and fixed]

**The premise was confirmed true empirically, not assumed.** Before writing any code: inserted a
row for org A into `unified_audit_events`, set `app.current_org_id` to org B, and queried both the
parent and the partition directly:

| Query target | Org B sees org A's row? |
|---|---|
| `unified_audit_events` (parent) | No (correct) |
| `unified_audit_events_2026_08` (partition, direct) | **Yes** — RLS bypassed entirely |

Postgres does not propagate a partitioned table's row-security enforcement onto its partitions for
direct access — each partition needs its own `ENABLE`/`FORCE ROW LEVEL SECURITY` and its own copy
of the policy. This is a real, confirmed cross-tenant data leak for any code path or ad-hoc query
that names a partition table directly instead of the parent.

**Scope drift found**: the tasklist's CURRENT STATE claims RLS is declared "on the PARENT" for
*both* `user_action_audit_logs` and `unified_audit_events`. Only the second half is true —
`user_action_audit_logs` (parent) has `relrowsecurity = false`, no policy, nothing to inherit. It
has no `organization_id`/`org_id` column at all (it scopes by `user_id`), so TASK 1.1's coverage
query correctly never flags it, and TASK 1.2's "inheritance" framing doesn't apply to it — there's
no existing policy to fail to inherit. Building real tenant scoping for it (a denormalized org_id
column, or a join-to-`users` policy, with the write-heavy-table performance tradeoffs that implies)
is a materially bigger design decision than this task's scope. **Flagged here as a real, separate,
unfixed gap** rather than silently expanded into or silently ignored.

**Fix implemented for `unified_audit_events`** (the table that does have a real policy to mirror):

- `V096__partition_direct_access_rls.sql` — iterates `pg_inherits` for every existing partition and
  applies the identical `ENABLE`/`FORCE ROW LEVEL SECURITY` + policy the parent already has.
- `PartitionMaintenanceJob` extended (SYSTEM 10 TASK 10.2 already refactored it into a
  `PARTITIONED_TABLES` loop, refactored further here into a table→policy map) so every future
  partition it creates gets the same treatment automatically — not just the ones existing today.
  `user_action_audit_logs` is deliberately absent from the policy map, for the same reason it's
  absent from V096.
- Tests: `RlsPartitionIsolationTest` (functional cross-org probe against a partition directly, plus
  a catalog-level "every partition has forced RLS + a policy" check) and a new
  `PartitionMaintenanceJobTest` method that strips RLS from an existing partition and proves the
  JOB itself (not just the migration) restores it.

**A real test-infrastructure bug found and fixed along the way, not a product bug**: the new
`PartitionMaintenanceJobTest` method passed in isolation but failed whenever run alongside the
pre-existing test in the same class. Root cause: `@SchedulerLock(..., lockAtLeastFor = "PT1M")`
silently no-ops a second direct call to `createUpcomingPartitions()` within a minute of the first —
correct or defensible production behavior on this repeatable-cron-triggered method, but a footgun
for tests that call the Spring-proxied bean directly and expect the method body to actually run
every time. Fixed by unwrapping the AOP proxy (`AopTestUtils.getUltimateTargetObject(...)`) in the
test so it calls the raw bean, bypassing ShedLock for what these tests are actually exercising (the
job's own logic, not its distributed-locking behavior).

**ACCEPTANCE** ("a direct `SELECT * FROM unified_audit_events_2026_08` under org B's GUC returns
zero of org A's rows") — met, both by the original manual probe and by
`RlsPartitionIsolationTest.directPartitionQuery_stillEnforcesOrgIsolation`.

## TASK 1.3 — Bypass-flag blast-radius test [DONE — mechanism already correct, now proven]

Read `PlatformAdminAccessGuard.java` and `RlsOrgIdHolder.java` first, per the task. The bypass flag
is **not** cleared inside `PlatformAdminAccessGuard` itself — it's cleared in
`RlsContextFilter.doFilterInternal`'s `finally` block, which wraps the entire filter chain
(everything a request does, including any `beginCrossOrgAccess`/`beginUnattendedCrossOrgAccess`
call and whatever runs after it). That's the right place for it: the bypass is documented as
lasting "for the rest of the request," and the outermost per-request filter is where "this request
is over, clean up" actually belongs.

`server/src/test/java/com/recoverpro/server/filter/RlsBypassBlastRadiusTest.java` — calls the real
`RlsContextFilter.doFilterInternal` (package-visible, called directly, not re-implemented) around a
real `PlatformAdminAccessGuard` (audit-writing dependencies mocked; the guard class itself, and its
actual write to the package-private `RlsOrgIdHolder.setBypass`, are real) that sets the bypass flag
and then throws mid-request. Covers both `beginCrossOrgAccess` (1.3.b) and
`beginUnattendedCrossOrgAccess` (1.3.c), plus a normal-request case proving org id is set from the
principal and cleared after.

**ACCEPTANCE** ("both tests pass; deliberately removing the finally-block clear makes them fail")
— met; verified the negative case by temporarily removing `RlsContextFilter`'s `finally` block,
confirming both bypass tests then fail with the bypass flag still `true`, then restoring it.

## Verification

`mvn -f server/pom.xml test -Dtest='Rls*Test,*Tenan*Test'` (the tasklist's own SYSTEM VERIFICATION
command) — all matching classes pass, including the pre-existing `EndToEndTenantIsolationTest`.

Full `mvn -f server/pom.xml test`: **627 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (up
from 619 at the end of the SYSTEM 41 session — 8 new: `RlsCoverageTest`×2,
`RlsPartitionIsolationTest`×2, `RlsBypassBlastRadiusTest`×3, plus 1 new method on the existing
`PartitionMaintenanceJobTest`).

## Rollup

All 3 tasks done, including the one (1.2) that started as "verify" and became "verify, find a real
leak, and fix it." Per the tasklist's own rule, SYSTEM 01 is checked `[x]` in the rollup — with two
flagged, deliberately out-of-scope follow-ups recorded above rather than silently resolved or
silently dropped: `user_action_audit_logs`'s complete lack of RLS, and `users`' structural
RLS-lessness (SYSTEM 09's territory).
