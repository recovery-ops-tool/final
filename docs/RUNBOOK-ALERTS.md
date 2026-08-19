# Alerts runbook

SYSTEM 12 TASK 12.3.b. Covers every alert this app can currently raise, across both delivery
channels: the in-process `OpsAlertService` email/notification path (working today, no external
infra required) and the Prometheus/Alertmanager rules in `ops/monitoring/alert-rules.yml` (written,
not yet deployed — see that file's header and "Delivery channels" below).

For each alert: what it means, the first three things to check, and how to mitigate.

## Delivery channels

**Email, via `OpsAlertService`** (`server/src/main/java/com/recoverpro/server/service/impl/OpsAlertServiceImpl.java`):
sends to `OPS_ALERT_RECIPIENT` (falls back to `CONTACT_EMAIL_RECIPIENT` if unset), subject prefixed
`[RecoverPro ops]`, 30-minute cooldown per alert name so a repeating failure doesn't flood the
inbox. Also creates an in-app notification for every `PLATFORM_ADMIN`. If neither recipient env var
is configured, the alert is **silently dropped** (a `log.warn` line only) — confirm
`OPS_ALERT_RECIPIENT` is set in production before trusting any alert below actually reaches anyone;
see `docs/CONFIG-REFERENCE.md`.

**Prometheus/Alertmanager** (`ops/monitoring/*.yml`): not deployed. No such service exists in
`docker-compose.yml` — adding one is a real resource-footprint decision on the single free-tier OCI
box this app targets (`docs/INFRA-CURRENT.md`), flagged for the user rather than committed to
silently. The two rules that need it (`HighServerErrorRate`, `*LatencyP99High`) require windowed
aggregation across every request that only Prometheus's histogram machinery does well; everything
else routes through email today, at effectively zero added resource cost.

## Email alerts (working today)

### Background job failed: `<jobName>`

**Source**: `OpsAlertService.alertJobFailure`, called from the catch block of every `@Scheduled`
method across all 14 scheduler classes (`server/src/main/java/com/recoverpro/server/scheduler/`),
plus `HikariPoolExhaustionMonitor`, `CircuitBreakerAlertListener`, `RedisUnreachableAlertMonitor`,
and both payment webhook controllers (all below have their own entry since they're higher-signal
than "a purge job threw once").

**Means**: a background job's exception escaped its own try/catch. The email body includes the
job name, context string, and the exception message; the full stack trace is in the server log,
not repeated in the email.

**First three checks**:
1. Grep the server log for the job name (`log.error` immediately precedes every alert) to get the
   full stack trace.
2. Check which job it is — some are purely operational (token cleanup), others are
   compliance/user-facing (see the per-scheduler comments added this session:
   `PingTtlScheduler.purge` is a data-retention purge, `MaintenanceScheduler.unlockExpiredAccounts`
   and `.autoEndDanglingShifts` are user/payroll-facing).
3. Check `SchedulerJobRunService`'s run-history table (if the job records to it) for how long this
   has been failing — one occurrence vs. a sustained streak changes urgency.

**Mitigation**: fix the underlying cause (usually a DB constraint violation, a null the job didn't
expect, or a downstream service being down) and let the next scheduled run retry. Jobs are
idempotent by design (purge/cleanup jobs re-select on each run), so a missed run is rarely
data-losing — the exception being unhandled is the bug, not the job's own logic. If the fix needs a
deploy, note in the ops channel that N runs were skipped so nobody re-alerts on the same root cause.

### `HikariConnectionPoolExhaustion`

**Source**: `HikariPoolExhaustionMonitor` (SYSTEM 02 TASK 2.3), polls `hikaricp.connections.pending`
every 30s, alerts once pending > 0 has held for 4 consecutive checks (≥ 90s sustained).

**Means**: every connection in the pool is checked out and requests are queueing for one. Under
sustained load this becomes request timeouts.

**First three checks**:
1. `/actuator/metrics/hikaricp.connections.active` vs `hikaricp.connections.max` — how close to the
   ceiling, and is it climbing or flat.
2. Slow query log / `pg_stat_activity` on Postgres for long-running or stuck queries holding
   connections open.
3. Recent deploys or traffic spikes that could explain a sudden increase in concurrent DB work.

**Mitigation**: kill any genuinely stuck query (`pg_terminate_backend`). If load-driven and
legitimate, `spring.datasource.hikari.maximum-pool-size` is the lever, bounded by Postgres's own
`max_connections` and how many other consumers (replicas, background jobs) share that budget. If
one endpoint is the culprit, look for a missing `@Transactional(readOnly = true)` or a query that
should be paginated but isn't.

### `CircuitBreakerOpen[<name>]`

**Source**: `CircuitBreakerAlertListener` (new this session), event-driven off Resilience4j's own
state-transition events — no polling delay, fires the instant the breaker trips. Covers `tts`,
`stt`, `llama`, `llamaEmbedding` (the four configured/annotated circuit breaker instances;
`server/src/main/resources/application.properties` has explicit tuning for the first three,
`llamaEmbedding` runs on Resilience4j's library defaults — a minor consistency gap, not urgent).

**Means**: that external integration's failure/slow-call rate crossed its configured threshold, and
calls are now short-circuiting straight to the fallback method instead of hitting the real service.
Users see degraded functionality (e.g. Lucien falls back to a canned response), not an error page.

**First three checks**:
1. Which breaker — `tts`/`stt` point at the voice pipeline, `llama`/`llamaEmbedding` at the LLM
   backend (Sarvam AI or Ollama, per `docs/INFRA-CURRENT.md`'s still-open AI-backend decision).
2. That service's own status/logs — is it actually down, or just slow (Resilience4j's
   `slow-call-duration-threshold` trips on latency too, not just errors)?
3. `/actuator/circuitbreakers` for the live state and failure-rate numbers.

**Mitigation**: usually nothing to do on this side but wait — the breaker moves to HALF_OPEN on its
own after `wait-duration-in-open-state` (30s for all three configured instances) and probes the
real service again. If the upstream is confirmed down for longer, communicate the degraded-mode
UX to support/users. A breaker stuck flapping OPEN/HALF_OPEN/OPEN for a long time is worth escalating
to whoever owns that integration.

### `RedisUnreachable`

**Source**: `RedisUnreachableAlertMonitor` (new this session), PINGs every 30s, alerts after 3
consecutive failures (≥ 60s sustained).

**Means**: Redis is down or unreachable from the app. Concretely this breaks: JWT blacklist checks
(`JwtAuthenticationFilter` fails **closed** — every authenticated request 503s, not just degrades),
SSE ticket redemption, live-track/SOS-audio pub/sub fan-out, and the `/actuator/health/readiness`
group (which already includes `redis`, so a load balancer polling readiness would pull the instance
from rotation on its own, once one exists).

**First three checks**:
1. Is the Redis process/container actually up (`docker compose ps redis`, or the managed Redis
   service's own status page if not self-hosted).
2. Network reachability from the app container specifically (not just "is Redis up somewhere") —
   check `REDIS_HOST`/`REDIS_PORT` match where it's actually listening.
3. Redis's own memory/eviction stats — an OOM-killed Redis looks like "unreachable" from the app's
   side.

**Mitigation**: restart/reprovision Redis. This is a hard outage, not a degraded mode — the JWT
filter's fail-closed design means no authenticated traffic succeeds until Redis is back, so this is
one of the highest-urgency alerts in this list despite routing through the same low-key email
channel as the others.

### `StripeWebhookController.dispatch` / `RazorpayWebhookController.dispatch`

**Source**: both webhook controllers' catch blocks (new this session — previously logged and
relied on the provider's own retry with zero paging).

**Means**: processing a Stripe/Razorpay webhook event threw. The event's claim is released so the
provider's automatic retry can re-attempt (both providers retry non-200 responses on a backoff
schedule), but if every retry also fails, this alert is the only signal an operator gets that a
subscription/payment state change never actually landed — money-critical, treat as higher urgency
than the generic job-failure alert even though it shares the same channel.

**First three checks**:
1. The alert body's `event=<id> type=<type>` — look that event up in the Stripe/Razorpay dashboard
   to see its own delivery/retry history and full payload.
2. Server log around the same timestamp for the actual exception (NPE from an unexpected payload
   shape, a DB constraint violation, an org lookup miss are the common causes).
3. Whether this event type has succeeded before — a first-time failure on a rarely-seen event type
   (e.g. a Stripe event type this app doesn't fully handle yet) is a different bug class than a
   previously-working type suddenly failing (more likely a schema drift or an upstream API change).

**Mitigation**: fix the bug and let the provider's own retry re-deliver — do not manually replay
unless the provider's retry window has already exhausted (check its dashboard). If retries are
exhausted, most providers offer a manual "resend this event" action from their dashboard, which is
safe given `claimEvent`'s idempotency key prevents double-processing.

## Prometheus/Alertmanager rules (written, not yet deployed)

Defined in `ops/monitoring/alert-rules.yml`. Will not fire until a real Prometheus + Alertmanager
stack is stood up and pointed at `/actuator/prometheus` — see that file's header for the deployment
decision this is waiting on, and note it also requires
`management.metrics.distribution.percentiles-histogram.http.server.requests=true` (not currently
set) before the two latency rules have any histogram buckets to compute a quantile over.

### HighServerErrorRate

**Means**: more than 5% of requests over a 5-minute window returned a 5xx. This is a starting
threshold, not a measured baseline — TASK 12.4 (SLOs) is where a real number replaces this once
production traffic data exists.

**First three checks**: 1) which endpoint(s) via `http_server_requests_seconds_count{status=~"5.."}`
grouped by `uri`, 2) server log for the actual exceptions in that window, 3) whether it correlates
with a recent deploy.

**Mitigation**: endpoint-specific — this rule only tells you something is broadly wrong, not what.

### LoginLatencyP99High / AllocationListLatencyP99High

**Means**: the 99th-percentile response time for `POST /api/v1/auth/login` (> 2s) or
`GET /api/v1/allocations` (> 3s) over a 5-minute window breached its threshold. These two were
named explicitly in TASK 12.3.a as the highest-traffic, most user-visible endpoints.

**First three checks**: 1) Hikari pool state (connection contention is the most common latency
cause for both — login hits the password hash + user lookup path, allocations is a list query),
2) whether `HighServerErrorRate` is also firing (timeouts often present as both), 3) DB query plan
for the allocation list query specifically if it's the one alerting (a missing index shows up here
before anywhere else).

**Mitigation**: endpoint-specific — see `docs/DB-HOTSPOTS.md` (SYSTEM 02) for the allocation-list
query's known plan, and the Hikari runbook entry above if pool exhaustion is the actual root cause.
