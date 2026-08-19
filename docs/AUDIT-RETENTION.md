# Audit log retention policy — proposal, pending sign-off

SYSTEM 10 TASK 10.3. **No purge logic has been implemented.** Per the task's own instruction, this
document proposes a policy and stops there until a human records sign-off in the section at the
bottom. `unified_audit_events`/`user_action_audit_logs` are append-only (immutability triggers
already block UPDATE/DELETE) and unbounded — that's correct for tamper-evidence, but it also means
nothing currently caps their growth or ever removes data RecoverPro may have a compliance
obligation *not* to keep indefinitely (SYSTEM 39's territory names this same tension).

## Scope: which tables this policy covers

Retention here is implemented by **dropping whole monthly partitions**, not `DELETE` — the
immutability triggers on these tables block `DELETE` entirely (V006/V016/V028's
`fn_audit_log_immutable()`/`prevent_audit_log_update()`), and that's by design, not a gap to work
around. Partition-drop only works on tables `PartitionMaintenanceJob` (TASK 10.2) actually
partitions monthly:

- `unified_audit_events` (V085)
- `user_action_audit_logs` (V016/V028)

**Out of scope**: `assignment_audit_logs`, `collection_audit_logs`, `ptp_audit_logs`,
`settlement_audit_logs`, `allocation_audit_logs` — these five per-domain tables are append-only
(V084 extended the same immutability trigger to them) but are **not partitioned**, so "drop the
partition" has nothing to act on. If they ever need retention, that requires a different mechanism
(the immutability trigger would need a deliberate carve-out, or they'd need retrofitting onto
partitioning first) — a separate decision, not proposed here.

## Why one horizon per table, not per category

`unified_audit_events.action` spans categories with very different natural retention needs — auth,
RBAC, and billing events are security/financial-sensitive; `FILE_PROCESSING_*`/`REPORT_GENERATED`
are closer to routine operational noise. A per-category policy would be the more precise design,
but partition-drop is **monthly and table-wide**: one partition holds every category's rows for
that month mixed together, so dropping it removes all of them regardless of `action`/`severity`.
Splitting by category would mean either a second parallel table (a larger schema change, not
proposed here) or accepting that the whole table shares one horizon.

Given that, the proposed horizon for `unified_audit_events` is set by its **most retention-sensitive
categories** (auth, RBAC, billing, org lifecycle, `USER_DATA_ERASED`), not its least — erring
toward keeping operational rows (`FILE_PROCESSING_*`, `REPORT_GENERATED`) longer than they strictly
need, rather than risking early deletion of a security or financial record. `user_action_audit_logs`
is a simpler, free-text "who clicked what" trail with no billing/RBAC content, so it gets the
shorter, operational-tier horizon.

## Proposed policy

| Table | Category (informal) | Proposed retention | Mechanism |
|---|---|---|---|
| `unified_audit_events` | Security & financial (whole table — see above) | **7 years** from `created_at` | Drop partitions with an upper bound older than `now() - 7 years` |
| `user_action_audit_logs` | Operational | **24 months** from `created_at` | Drop partitions with an upper bound older than `now() - 24 months` |

This mirrors the tasklist's own suggested default for a collections/recovery product handling
financial and personal data: "security and financial events retained 7 years, operational events
12-24 months" — 24 (the longer end of that range) chosen for the operational table since it's the
safer direction to be wrong in, and there's no cost pressure at current data volumes forcing the
shorter end.

**This is a proposed default, not a researched legal requirement** — it is not a substitute for
actual legal/compliance review of RecoverPro's obligations under whatever jurisdictions and
regulations it operates in (India's DPDP Act and any RBI/financial-recovery-sector retention rules
most plausibly, given the product's domain — SYSTEM 39, Compliance/Privacy, is where that broader
review lives). Treat the numbers above as a placeholder sane enough to implement against, to be
corrected by whoever does that review rather than blocking this task indefinitely on it.

## Implementation plan (ready to build once signed off — not built yet)

1. Extend `PartitionMaintenanceJob` (same `@SchedulerLock`-guarded scheduled method TASK 10.2
   already built, or a sibling method in the same class) with a purge pass per partitioned table:
   for each existing partition whose upper date bound is older than that table's horizon, `DROP
   TABLE` the partition (not `DELETE` the rows in it).
2. **Dry-run by default.** A new config flag (e.g. `app.audit.retention.purge-enabled`, default
   `false`) gates whether drops actually execute. When disabled, the job logs exactly which
   partitions it *would* drop (name, row count, age) and drops nothing — matching the task's own
   acceptance wording verbatim.
3. Every actual drop logged at INFO with the partition name and its row count *before* dropping
   (a `SELECT count(*)` against the partition, then `DROP TABLE`) — a retention job that silently
   removed data with no trace of what or how much would be its own audit gap.
4. No retroactive backfill logic needed — `PartitionMaintenanceJob`'s existing partitions are all
   recent (TASK 10.2's 3-month lookahead), so the horizon won't have anything to drop for a long
   time after this ships; the job existing now just means growth is capped going forward and the
   mechanism is proven (via dry-run output) before it ever needs to remove anything for real.

## Sign-off

**Status: ACCEPTED AS PROPOSED**, 2026-08-19 — user confirmed via the running session, in response
to an explicit sign-off question naming the exact numbers and mechanism above (7 years for
`unified_audit_events`, 24 months for `user_action_audit_logs`, partition-drop, dry-run by
default). Purge logic implemented same day — see "Implementation" below.

- [x] Accepted as proposed (7 years / 24 months)
- [ ] Accepted with changes: _______________________
- [ ] Deferred — reason: _______________________

Signed off by: RecoverPro product owner (via session confirmation)  Date: 2026-08-19

## Implementation (2026-08-19, post sign-off)

Built exactly as planned above, into the same `PartitionMaintenanceJob` class TASK 10.2 already
owns:

- `PartitionMaintenanceJob.purgeExpiredPartitions()` — a second `@Scheduled` method (`0 15 2 25 * *`,
  15 minutes after TASK 10.2's creation pass) under its own `@SchedulerLock(name =
  "partition_retention_purge")`, so a lock or failure on one pass never blocks or masks the other.
  Retention horizons come from new `AppProperties.Audit` fields
  (`unifiedAuditEventsRetentionYears=7`, `userActionAuditLogsRetentionMonths=24`,
  `retentionPurgeEnabled=false` by default), not hardcoded — configurable per environment without a
  redeploy.
- Discovers each table's real partitions via `pg_inherits`/`pg_class` (not a guessed date range),
  parses each partition's own `<table>_yyyy_MM` name (the same `MONTH_FMT` TASK 10.2 already uses to
  create them) to get its month, and skips anything that doesn't parse that way — most importantly
  the DEFAULT partition, which is never purge-eligible.
- Dry-run by default: every partition past its horizon is logged at INFO either way
  (`(DRY RUN, would drop)` vs. an actual drop line), naming the partition, its month, the cutoff,
  and an approximate row count. Only `DROP TABLE IF EXISTS` when `retentionPurgeEnabled=true`.
- Row count uses `pg_class.reltuples` (a planner estimate), not `SELECT count(*)` — found live while
  building this: `unified_audit_events` partitions carry FORCE RLS (TASK 10.2), and this job's own
  connection has no org context or platform-admin bypass (a system-wide sweep across every org's
  rows isn't "one admin reading one tenant's data," so `PlatformAdminAccessGuard`'s bypass — which
  requires naming a single target org — is the wrong tool, not just an inconvenient one). A direct
  `count(*)` would have silently logged 0 for every `unified_audit_events` drop with no error to
  reveal it; `reltuples` is catalog metadata, not subject to RLS, and accurate enough for an
  informational log line given a past-retention partition is cold (no recent writes to skew the
  estimate).
- Test coverage: `PartitionMaintenanceJobTest` — dry-run drops nothing, enabled mode drops only
  partitions past their table's own horizon (including a real `unified_audit_events` partition with
  FORCE RLS applied, proving the `reltuples` read isn't blocked or silently zeroed the way
  `count(*)` would be), and the DEFAULT partition is never touched either way.
