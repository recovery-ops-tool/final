# Service-level objectives

SYSTEM 12 TASK 12.4. Per the task's own instruction ("base them on measured baselines from
12.1/12.2, not aspirations"): the only measured data that exists anywhere in this codebase is
`docs/DB-HOTSPOTS.md`'s pg_stat_statements harvest — and that's dev-scale traffic (the server test
suite exercising real query shapes, not production volume) against a local Postgres instance, not
production. **No live OCI instance exists yet** (`docs/INFRA-CURRENT.md`), so there is no real
production baseline to measure from. What follows are starting targets, generously above the only
real numbers available, explicitly flagged as needing replacement once real traffic exists — not a
substitute for that measurement.

## Availability

**Target: 99.5% monthly uptime** (≈ 3h40m/month of allowed downtime). Not measured from anything —
there's no uptime history to derive it from. Chosen as a reasonable starting point for a
single-instance deployment on free-tier hardware (`docs/INFRA-CURRENT.md`: one OCI Ampere A1
instance, no redundancy) — a multi-instance/HA target (99.9%+) isn't honest to promise against
infrastructure that has a single point of failure by construction. Revisit once SYSTEM 05's
readiness-probe verification (`docs/SYSTEM-05-INFRASTRUCTURE.md`, the two checks still marked
unverified) is confirmed on a real host, and once there's actual uptime history to measure against.

## Latency — the three most-used endpoints

Task 12.1's own alert list already named two of these (`POST /api/v1/auth/login`,
`GET /api/v1/allocations`); the third, `GET /api/v1/daily-dispatch` (the field agent's daily work
list — `DailyDispatchController`), is the other consistently-hit endpoint across the app based on
which controllers exist and how central allocation/dispatch workflows are to the product.

| Endpoint | p50 target | p99 target | Basis |
|---|---|---|---|
| `POST /api/v1/auth/login` | < 300ms | < 2s | No direct pg_stat_statements row (password hashing dominates, not DB time) — targets are a starting guess, not derived. |
| `GET /api/v1/allocations` | < 500ms | < 3s | `DB-HOTSPOTS.md`: the equivalent query's dev-scale mean was 0.363ms (111 calls) / 23.5ms (1 paginated call, 161 rows). Targets are ~100-1000x that dev-scale number, not because the query is expected to be that slow, but because dev-scale row counts (dozens) are nowhere near what an org's real allocation book looks like, and network/serialization/auth overhead isn't in the DB-only number at all. |
| `GET /api/v1/daily-dispatch` | < 500ms | < 3s | No direct pg_stat_statements row captured for this specific query — same reasoning as allocations (similar shape: org-scoped list query) applied by analogy, not measured directly. |

**These are placeholders, not commitments.** Replace every row here with real p50/p99 numbers from
`http_server_requests_seconds` (Prometheus, once `ops/monitoring/` is actually deployed — see
`docs/RUNBOOK-ALERTS.md`) or from application logs, measured against real production traffic, as
soon as that traffic exists. The `ops/monitoring/alert-rules.yml` thresholds (`LoginLatencyP99High`
at 2s, `AllocationListLatencyP99High` at 3s) were set to match these same placeholder targets —
update both together so the alert and the SLO it's protecting don't drift apart.

## File-import success rate

**Target: 99% of file-import jobs reach COMPLETED or PARTIALLY_COMPLETED (not FAILED).** No
measured baseline exists — `file_import_events_total{upload_type,outcome}` (added this task, TASK
12.2) is the metric that will produce one, but it has never run against real usage yet. A
`FAILED` outcome (as opposed to `PARTIALLY_COMPLETED`, which means some rows succeeded) means the
whole import aborted before processing any rows — usually a malformed file or a required-header
mismatch, which is arguably a user-input problem more than a reliability one, but it's the metric
the task asked for, so it's tracked as stated.

**Revisit once measured**: `sum(rate(file_import_events_total{outcome=~"completed|partially_failed"}[30d])) / sum(rate(file_import_events_total{outcome="started"}[30d]))`
is the query once Prometheus exists; until then, the raw counters are still readable directly off
`/actuator/prometheus` or `unified_audit_events`'s `FILE_PROCESSING_*` rows for a manual check.

## Error budget

Not formally tracked (no burn-rate alerting, no error-budget dashboard) — flagged as a gap, not
attempted this task. The `HighServerErrorRate` Prometheus rule (`ops/monitoring/alert-rules.yml`)
is a flat 5%-over-5-minutes threshold, not a rolling error-budget calculation against the 99.5%
availability target above. Building real error-budget tracking is meaningful additional work
(typically a multi-window burn-rate alert, per the SRE workbook pattern) that wasn't in TASK 12.4's
scope and would itself want real traffic to tune against — noted here so it isn't silently assumed
to exist.
