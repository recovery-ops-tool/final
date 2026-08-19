# SYSTEM 12 — Monitoring & Observability: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 12 block, 2026-08-19 session — picked next
by priority order among the 5 fully-untouched P0 systems (12/13/18/19/39), continuing straight
from finishing SYSTEM 09 (Authorization/RBAC) the same session.

## TASK 12.1 — Export metrics [DONE]

`micrometer-registry-prometheus` was already a dependency and `management.endpoints.web.exposure.include`
already listed `prometheus`, `circuitbreakers`, `circuitbreakerevents` — looked complete on paper.
**Wrote a real integration test against a running instance instead of trusting that** (this
codebase has already been burned once by config that looked right but didn't actually boot —
SYSTEM 05's Flyway readiness group) — and it failed: `/actuator/prometheus` returned a genuine 500
(`NoResourceFoundException: No static resource actuator/prometheus`), not a 401/403.

**Root cause, confirmed by decompiling `spring-boot-actuator-autoconfigure-3.4.13.jar`'s own
`spring-configuration-metadata.json`**: `management.metrics.export.prometheus.enabled` (the
property this app had set to `true`) is deprecated at `"level": "error"` in Spring Boot 3.4 — it no
longer binds to anything at runtime. The replacement property is
`management.prometheus.metrics.export.enabled`, which was never set, so the endpoint never
registered at all. Confirmed via a live authenticated request to `/actuator` before the fix (5
endpoints exposed, no `prometheus` link) and after (6 endpoints, `/actuator/prometheus` returns
200 with real metric bodies). Fixed in `server/src/main/resources/application.properties`.

This means `/actuator/prometheus` has been non-functional this entire time — a real production gap
now closed, not a hypothetical one caught early.

**Test**: `MetricsExportTest` (new) — real `@SpringBootTest(RANDOM_PORT)` instance, asserts the
endpoint is reachable with no JWT (network-level security, matching SYSTEM 02's established
reasoning for this same endpoint) and the response body contains `http_server_requests_seconds`,
`resilience4j_circuitbreaker_state`, and `hikaricp_connections_pending`.

**ACCEPTANCE**: met, verified live.

## TASK 12.2 — Business and integration metrics [DONE]

Instrumented every metric the task names by example, each at the single existing choke point for
that concern (no new abstraction layers):

- **File imports**: `file_import_events_total{upload_type,outcome}` (started/completed/
  partially_failed/failed) and `file_import_rows_processed_total{upload_type,result}` in
  `FileProcessingServiceImpl`, at the three points that already decide status (STARTED audit point,
  `finalizeEmptyFile`, `finalizeProcessing`).
- **Payment webhooks**: `payment_webhook_events_total{provider,outcome}` in both
  `StripeWebhookController` and `RazorpayWebhookController` (processed/duplicate/invalid_signature/
  failed/deserialization_skipped).
- **Lucien LLM**: `lucien_llm_call_duration_seconds{operation,outcome}`,
  `lucien_llm_call_events_total{operation,outcome}` (including a `circuit_open` outcome from the
  fallback methods), and `lucien_llm_tokens_total{token_type}` (prompt/completion) in `LlamaClient`
  — token volume specifically because it's a direct cost driver once the AI backend is a paid
  hosted API (Sarvam AI, per `docs/INFRA-CURRENT.md`), which is the actual reason this task calls
  it out by name.
- **Notification/email send success rate**: `email_send_events_total{outcome}`
  (sent/failed/skipped_unconfigured) in `EmailServiceImpl`'s single private `send()` method that
  every other public method already funnels through.
- **Report generation duration**: `report_generation_duration_seconds{reportType,format,outcome}`
  in `ReportJobExecutor`, computed from the job's own `startedAt`/`completedAt` timestamps.

Deliberately **not** tagged by organization anywhere (task's own guidance: cardinality) — every tag
above is a small fixed set (upload type, provider, operation, outcome, report type/format).

**Tests**: extended three existing tests (`FileProcessingServiceImplTest`,
`EmailServiceImplTest`, `ReportJobExecutorTest`) — each already exercises the relevant code path,
so each got one assertion pulling the real counter/timer back out of a real `SimpleMeterRegistry`
(not a mock — Micrometer's own recommendation, and the same API `/actuator/prometheus` reads from)
to prove the metric actually appears, per the task's own literal acceptance wording. Didn't add
dedicated new tests for the webhook controllers or `LlamaClient` — no existing tests construct
either directly (confirmed by grep), and building Stripe/Razorpay signature-forging test
infrastructure from scratch was judged disproportionate to what a mechanical counter-increment
addition needs; verified instead by full-suite regression (687 tests, 0 failures) plus direct code
review.

**ACCEPTANCE**: met for the three metrics with direct test proof; the other two (webhooks, LLM)
verified by compilation + full-suite pass + code review, not a dedicated metric-appears test.

## TASK 12.3 — Alert rules and runbooks [DONE]

**12.3.c (verify OpsAlertService's delivery destination) done first**, since it gates whether
anything else in this task matters: real email via `spring.mail.*`, to `OPS_ALERT_RECIPIENT`
(falls back to `CONTACT_EMAIL_RECIPIENT`), 30-minute cooldown per alert name, plus an in-app
notification to every `PLATFORM_ADMIN`. Confirmed via `OpsAlertServiceImplTest` (pre-existing) —
"a deliberately failed scheduled job produces a delivered alert" was already true and tested for
the generic case. If neither recipient env var is set in production, alerts are silently dropped
(a log line only) — this is the literal SYSTEM 04 finding `docs/CONFIG-REFERENCE.md` already
flagged ("how the reconciliation scheduler died silently once"); worth a final pre-launch check
that `OPS_ALERT_RECIPIENT` is actually set.

**12.3.a (define alerts) — found 5 real gaps, not just "define" from scratch**:
- **3 of 14 scheduled-job classes had zero failure alerting**: `LucienSessionTtlJob`,
  `PingTtlScheduler`, `MaintenanceScheduler` (5 methods) — no try/catch at all, an exception would
  silently vanish into Spring's default scheduler error log. `PingTtlScheduler.purge` is a
  data-retention purge (agent location pings, 30-day TTL) — a silent failure there is a compliance
  gap, not just an operational one. `MaintenanceScheduler.unlockExpiredAccounts` and
  `.autoEndDanglingShifts` are user/payroll-facing. Fixed all 7 methods with the same
  try/catch-then-`OpsAlertService.alertJobFailure` pattern already used by the other 11 schedulers.
- **Both payment webhook controllers logged-and-retried on failure but never paged anyone** — if
  Stripe/Razorpay's own retries also exhausted, the only trace was a server log line. Fixed both
  (see TASK 12.2 above — same edit added both the alert and the metric).
- **Circuit breaker OPEN and Redis unreachable had no alerting path of any kind** — the task names
  both explicitly. No live Prometheus/Alertmanager exists to consume a metric-threshold rule for
  either (see below), so built two new in-process monitors reusing the existing `OpsAlertService`
  path, matching the precedent `HikariPoolExhaustionMonitor` (SYSTEM 02 TASK 2.3) already set for
  exactly this situation:
  - `CircuitBreakerAlertListener` (new) — event-driven off Resilience4j's own state-transition
    events (no polling delay), alerts on any CLOSED/HALF_OPEN → OPEN transition across all 4
    circuit breaker instances (`tts`, `stt`, `llama`, `llamaEmbedding` — including the last one,
    which isn't in `application.properties`'s explicit config and is only created lazily on first
    use, handled via the registry's `onEntryAdded` event so it's still caught).
  - `RedisUnreachableAlertMonitor` (new) — polls `PING` every 30s, alerts after 3 consecutive
    failures (≥ 60s sustained), matching Hikari's "sustained, not a blip" pattern. Fails **open**
    (still alerts) on its own Redis errors, the opposite of the JWT blacklist check's fail-closed
    choice — losing the audit/alert trail during an outage is worse than a few extra writes.
- **5xx rate and p99 latency (login + allocation-list)** genuinely need Prometheus's windowed
  aggregation, which the in-process pattern above can't reasonably replicate. Wrote
  `ops/monitoring/alert-rules.yml` + a companion `prometheus.yml` scrape config, ready to deploy —
  but did **not** add a live Prometheus/Alertmanager service to `docker-compose.yml`. The
  deployment target is a single free-tier OCI instance (2 OCPU/12GB, `docs/INFRA-CURRENT.md`)
  already running Postgres/Redis/backend/web/Caddy/Ollama; adding a monitoring stack is a real
  resource-footprint decision on that box, not a reversible local file edit — flagged for the user
  explicitly, same reasoning `INFRA-CURRENT.md` already applies to `tts-service` ("not wired in
  here — flag/ask before adding it to compose"). The two rules also need
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true` (not currently
  set) before they have histogram buckets to compute a quantile over — noted in the file, not
  enabled unilaterally for the same reason (small ongoing per-request cost, best decided alongside
  standing up the thing that would actually consume it).

**12.3.b (runbook) done**: `docs/RUNBOOK-ALERTS.md` — every alert in the list above, both delivery
channels, each with what it means / first three checks / mitigation.

**Tests**: `RedisUnreachableAlertMonitorTest`, `CircuitBreakerAlertListenerTest` (new — the latter
uses a real `CircuitBreakerRegistry`/`CircuitBreaker`, not mocks, since mocking Resilience4j's own
event-publishing machinery would just prove a mock does what it's told).

**ACCEPTANCE**: "hitting an endpoint without permission produces exactly one ACCESS_DENIED audit
row naming the endpoint" is SYSTEM 09's acceptance text, reused verbatim in this task's
block — for SYSTEM 12 the equivalent is "every alert rule has a runbook entry; a deliberately
failed scheduled job produces a delivered alert," both met.

## TASK 12.4 — SLOs [DONE, placeholder targets]

`docs/SLO.md` — availability (99.5%, chosen for a single-instance free-tier deployment with no
redundancy, not derived from anything since no uptime history exists), latency targets for the
three most-used endpoints (login, allocation-list, daily-dispatch), and file-import success rate.

**Honest about what "measured baseline" can mean here**: no live production instance exists
anywhere (`docs/INFRA-CURRENT.md`), so there is no real production traffic to measure from. The
*only* real number in this codebase is `docs/DB-HOTSPOTS.md`'s dev-scale pg_stat_statements harvest
(test-suite traffic against local Postgres, not production volume) — cited explicitly as the sole
empirical anchor, with targets set well above it specifically because dev-scale row counts aren't
production-representative, not because the query is expected to be that slow. Every number in the
doc is flagged as a placeholder to replace once real traffic exists, not fabricated as if it were
already measured.

**ACCEPTANCE** (task's literal wording, "docs/SLO.md exists with measured baselines cited"): the
file exists and cites the one real measurement that's actually available, with explicit,
un-glossed-over caveats about what it isn't (production data) rather than presenting placeholder
numbers as if they were measured production baselines.

## New/changed files this session

- `server/src/main/resources/application.properties` — fixed the dead Prometheus-export property.
- `server/src/main/java/com/recoverpro/server/observability/CircuitBreakerAlertListener.java` (new)
- `server/src/main/java/com/recoverpro/server/observability/RedisUnreachableAlertMonitor.java` (new)
- `server/src/main/java/com/recoverpro/server/scheduler/LucienSessionTtlJob.java`,
  `PingTtlScheduler.java`, `MaintenanceScheduler.java` — added failure alerting.
- `server/src/main/java/com/recoverpro/server/controller/StripeWebhookController.java`,
  `RazorpayWebhookController.java` — added failure alerting + webhook metrics.
- `server/src/main/java/com/recoverpro/server/service/impl/FileProcessingServiceImpl.java`,
  `EmailServiceImpl.java`, `ReportJobExecutor.java`,
  `server/src/main/java/com/recoverpro/server/client/LlamaClient.java` — TASK 12.2 metrics.
- `ops/monitoring/alert-rules.yml`, `ops/monitoring/prometheus.yml` (new, not deployed)
- `docs/RUNBOOK-ALERTS.md`, `docs/SLO.md` (new)
- New tests: `MetricsExportTest`, `CircuitBreakerAlertListenerTest`,
  `RedisUnreachableAlertMonitorTest`; extended `FileProcessingServiceImplTest`,
  `EmailServiceImplTest`, `ReportJobExecutorTest` with metric assertions;
  `JwtAuthenticationFilterBlacklistFailClosedTest`-style constructor updates were NOT needed here
  (no existing test constructed the changed scheduler/webhook classes directly).

## Verification

Full `mvn -f server/pom.xml clean test`: **687 tests, 0 failures, 0 errors, 2 skipped** (up from
the 678-test checkpoint SYSTEM 09 left this session at; +9 new tests, no regressions).

**Also ran the tasklist's own literal SYSTEM VERIFICATION command, for real, against a real running
instance** — unlike SYSTEM 09's equivalent (whose specified `-Dtest=` pattern matched zero actual
test classes and was flagged, not run): packaged a real jar (`mvn package -DskipTests`), booted it
on `localhost:8080` against local Postgres/Redis, and ran the exact command the tasklist
specifies:

```
$ curl -s localhost:8080/actuator/prometheus | grep -c '^[a-z]'
329
```

Non-zero, as required. Also spot-checked the two metric names TASK 12.1 names explicitly, straight
off the live scrape: `hikaricp_connections_pending{pool="primary"} 0.0` and six
`resilience4j_circuitbreaker_state{name="llama",state=...}` gauge lines (one per state, `closed`
correctly at `1.0`) — both present and correctly formed. Instance stopped cleanly after.

All 4 tasks (12.1, 12.2, 12.3, 12.4) done. **Rollup checkbox: [x].**
