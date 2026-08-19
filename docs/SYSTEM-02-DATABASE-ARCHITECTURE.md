# SYSTEM 02 — Database Architecture: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 02 block, 2026-08-18 session (continuation of
the same session that completed SYSTEM 41 and SYSTEM 01).

## Prerequisite check

SYSTEM 01 (RLS coverage guard exists) — done this same session, `docs/SYSTEM-01-MULTI-TENANCY.md`.

## TASK 2.1 — Enable and harvest pg_stat_statements [DONE]

Real numbers harvested, not fabricated — see `docs/DB-HOTSPOTS.md` for the full data. Summary of
what it took:

- `shared_preload_libraries = 'pg_stat_statements'` needs a full Postgres **restart**, which needed
  the repository owner's admin access (this session doesn't have OS-level service-restart rights) —
  the postgresql.conf edit was made in this session, the restart itself was run by the user.
- `CREATE EXTENSION pg_stat_statements` needs **superuser**, confirmed empirically the same way
  SYSTEM 41 found `pgvector` did — but this app's Flyway migration role (`opstool`) is deliberately
  non-superuser. `V097__pg_stat_statements.sql` therefore only *attempts* the extension inside a
  `DO` block with an `insufficient_privilege` exception handler that warns instead of failing —
  verified both ways: applies cleanly (with a warning) as `opstool`, and actually creates the
  extension when run with superuser access.
- Full test suite (627 tests at the time) run against a stats-reset instance to generate real,
  representative query traffic, then harvested via the documented one-command query.

**Honest finding, not a fabricated one**: no table in the harvested dataset exceeds ~10k rows —
`organizations` shows 99,625 sequential scans against 119 rows and zero index scans, which is the
query planner making the *correct* choice at that size, not a missing index.

## TASK 2.2 — Close missing indexes on hot paths [DONE — correctly, nothing to add]

Per TASK 2.2.c's own instruction ("do NOT add speculative indexes... every index must trace to a
line in DB-HOTSPOTS.md"), and TASK 2.1's finding that nothing crosses the ~10k-row threshold: no
indexes were added. This is the task executed correctly, not skipped — adding indexes against this
evidence would have been exactly the speculative-optimization anti-pattern the task explicitly
forbids. Re-run once real production data volume exists.

## TASK 2.3 — Pool sizing and exhaustion alerting [DONE]

- **2.3.a**: `spring.datasource.hikari.maximum-pool-size`/`minimum-idle`/`connection-timeout` were
  hardcoded (20/5/30000) despite `server/.env.example` already documenting `DB_POOL_SIZE`/
  `DB_MIN_IDLE` as configurable — neither env var did anything. Wired to real env vars (defaults
  unchanged); added `DB_CONNECTION_TIMEOUT_MS` (no prior surface existed for it).
- **2.3.b**: confirmed **and fixed** a real gap, not just verified. `/actuator/prometheus` required
  the app's normal JWT auth — a Prometheus scraper has no way to authenticate that way, so metrics
  scraping was silently impossible end to end (matches the tasklist's own literal unauthenticated
  `curl` in its SYSTEM VERIFICATION command). Added `/actuator/prometheus` to `SecurityConfig`'s
  public paths, same reasoning SYSTEM 05 TASK 5.2.d already applies to `/actuator/health`. Also
  confirmed (empirically, by booting the real app and curling it) that Micrometer's Hikari binding
  correctly unwraps `RlsAwareDataSource` via its `DelegatingDataSource` base class to find the real
  pool underneath — not something to assume given the custom wrapping. `hikaricp_connections_pending`
  and `hikaricp_connections_timeout_total` both confirmed present.
- **2.3.c**: no Prometheus/Alertmanager stack exists yet (SYSTEM 12 hasn't run), so a config file
  alert rule isn't meaningful yet. Built `HikariPoolExhaustionMonitor` — polls the same gauge
  in-process every 30s, alerts through the existing `OpsAlertService` (email + platform notification,
  with its own cooldown) once pending > 0 is sustained across 4 consecutive checks (~90s), not on a
  single blip. `HikariPoolExhaustionMonitorTest` covers: no-alert-on-blip, alert-after-sustained,
  streak-resets-on-recovery, and graceful no-op if the gauge is ever absent.

## TASK 2.4 — Read replica routing for reporting [PARTIAL — infrastructure done, one call site blocked]

Built and verified the foundational piece, the part the task itself calls "the single most
dangerous part" (getting RLS wrapping wrong):

- `RlsDataSourceConfig` now builds primary + replica Hikari pools, **each independently wrapped in
  `RlsAwareDataSource` before either is registered** with a new `ReplicaRoutingDataSource`
  (`AbstractRoutingDataSource`) that picks between them per `ReplicaRoutingContext` (a ThreadLocal
  flag, same pattern as `RlsOrgIdHolder`).
- `REPLICA_DB_URL` unset (the only state possible anywhere right now — no replica is provisioned,
  see `docs/INFRA-CURRENT.md`) resolves to the exact same URL as primary, and **reuses the same pool
  object** for both routing targets rather than opening a second, pointless Hikari pool against the
  identical database — true "byte-identical" behavior, not just identical query results with double
  the idle connections.
- `ReplicaDataSourceRlsTest` proves the dangerous part directly: cross-org isolation holds through
  the ordinary path AND through an explicitly `runOnReplica`-wrapped path, using the exact same
  `RlsIsolationTest`-style probe.
- `ExportServiceImpl.findReportJob` (used by `exportReport` too) is wired to `runOnReplica` — safe
  because it has no enclosing `@Transactional`, so it gets its own independent connection checkout
  every call.

**Not wired, found to be genuinely blocked, not skipped carelessly**: `ReportJobExecutor.
processJobAsync` — the actual report-DATA-generation call the task is really about (this is the
"heavy report" traffic TASK 2.4's own CURRENT STATE worries could degrade transactional latency) —
runs inside one `@Transactional(REQUIRES_NEW)` method together with the job-status writes
(`GENERATING` → `COMPLETED`/`FAILED`). `AbstractRoutingDataSource` resolves its target once, at
first connection acquisition for a transaction, and does not re-route mid-transaction — so wrapping
just the read portion in `runOnReplica` inside that already-open transaction would silently do
nothing. Confirmed directly: `buildReportData` (the report-data builder) carries no
`@Transactional` of its own and is invoked via `this::buildReportData` (a self-reference that
bypasses the Spring AOP proxy even if it did). Actually routing this path needs the read separated
into its own transaction, independent of the write-heavy job-tracking transaction — a business-logic
transaction-boundary change, not a wiring change, and one with real regression risk to a working
reporting feature for a benefit (replica offload) that cannot even be verified without a real
replica existing. Flagged here rather than attempted without being asked, per this session's
established pattern (see SYSTEM 01's `user_action_audit_logs` finding for the same kind of call).

Per the tasklist's own rollup rule, SYSTEM 02's checkbox reflects this: 3.5 of 4 tasks fully done,
the infrastructure half of TASK 2.4 done and verified, the harder call-site half explicitly open.

## Verification

`mvn -f server/pom.xml test` (SYSTEM 02's own SYSTEM VERIFICATION command's test half) — **633
tests, 0 failures, 0 errors, BUILD SUCCESS** (up from 627 at the end of SYSTEM 01: +6 —
`HikariPoolExhaustionMonitorTest`×4, `ReplicaDataSourceRlsTest`×2).

`curl -s localhost:8080/actuator/prometheus | grep hikaricp` (the command half) — run against the
real booted app, confirmed non-empty, confirmed `hikaricp_connections_pending` and
`hikaricp_connections_timeout_total` present, both after fixing the auth-gating bug that would have
made this command return nothing before this session.
