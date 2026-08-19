# SYSTEM 05 — Infrastructure: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 05 block (LAYER 0, P1), 2026-08-18 session.
Investigation for all three tasks was done in a prior session and carried over via memory; this
session implemented directly from those findings, found one more real bug along the way, and
verified with a real `mvn test` run and a real packaged-jar boot.

## Prerequisite check

SYSTEM 04 (Environment & Config Management) — done, per `docs/SYSTEM-04-ENVIRONMENT-CONFIG-MANAGEMENT.md`.

## TASK 5.1 — Confirm and document the deployment target [DONE]

`docs/INFRA-CURRENT.md` already had the user-confirmed target (OCI) from a prior session. This
session added the gaps found during investigation that the doc didn't yet cover:

- `.github/workflows/` existence confirmed (`server-ci.yml`, `web-ci.yml`, `mobile-ci.yml`) — full
  content/coverage still SYSTEM 06's job.
- `Caddyfile` has a literal unfilled placeholder domain (`yourdomain.com`) — flagged, not guessed at.
- `tts-service/` has no service block in `docker-compose.yml` at all — not wired into the
  deployment. Deliberately not silently added, since it's tangled up with the still-open Sarvam AI
  vs. self-hosted-TTS decision (`docs/INFRA-CURRENT.md`'s "AI backend" section).
- Real request chain documented: Caddy → nginx (inside the `web` container) → Spring Boot
  `backend`, two hops, both of which set/forward `X-Forwarded-For`.
- **`docker-compose.yml` now pins an explicit bridge subnet** (`172.28.0.0/16`, top-level
  `networks.default.ipam.config`) — previously relied on Docker's auto-assigned subnet, which is
  not stable across recreations. `TRUSTED_PROXY_CIDR` (SYSTEM 04's still-open item) should target
  this subnet in production, **but as the regex `172\.28\.\d{1,3}\.\d{1,3}`, not the literal CIDR
  string** — see the correction in `docs/CONFIG-REFERENCE.md` (found during SYSTEM 07 TASK 7.1:
  Tomcat's `RemoteIpValve` compiles this value as a Java regex, so the CIDR-notation value written
  here originally would never have matched a real IP).

## TASK 5.2 — Liveness/readiness probes [DONE]

`management.endpoint.health.probes.enabled=true` + `livenessState`/`readinessState` enabled in
`server/src/main/resources/application.properties`, exposing `/actuator/health/liveness` and
`/actuator/health/readiness` (Spring Boot's `AvailabilityState`-backed probe endpoints, separate
from the aggregate `/actuator/health`). `server/Dockerfile`'s `HEALTHCHECK` switched from the
aggregate endpoint to `.../readiness`, since that's the one that actually reflects "should this
instance receive traffic," not just "is the process alive."

**Prerequisite bug fixed first, before any of the above would have worked**:
`SecurityConfig.PUBLIC_PATHS` had the exact string `"/actuator/health"` (no `/**` wildcard), and
Spring Security's `.requestMatchers(PUBLIC_PATHS).permitAll()` is exact-match — so the two new
probe sub-paths would have 401'd every load-balancer check. Changed to `"/actuator/health/**"`.

Readiness group extended beyond the default (`readinessState` alone) to `readinessState,redis,db`.
`db` goes beyond the task's literal "Redis and Flyway" wording — a database that's down is at
least as much a reason to stop receiving traffic as Redis is, and this was decided rather than
asked, matching this session's established pattern for uncontroversial reasoned completions.

**`flyway` is deliberately NOT in the readiness group, contrary to the task's literal wording and
this session's own earlier investigation notes.** Real bug found by actually running `mvn test`
after wiring it in: Spring Boot 3.4.13 ships no Flyway health *contributor* at all — verified
directly by inspecting `spring-boot-actuator-autoconfigure-3.4.13.jar`'s
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, which lists
only `FlywayEndpointAutoConfiguration` (the `/actuator/flyway` migration-info endpoint, not a
health indicator) — no `FlywayHealthContributorAutoConfiguration` class exists to find. Naming a
nonexistent contributor in `management.endpoint.health.group.readiness.include` doesn't degrade
gracefully: Spring Boot's health-group-membership validation refuses to let the
`ApplicationContext` start *at all*. This broke ~40 of the ~44 integration test classes (every one
building the app's default `ApplicationContext`) with `NoSuchHealthContributorException`, and would
have broken production boot outright — not merely made the readiness probe unreliable. A hand-rolled
`HealthIndicator` calling `Flyway.info()` was considered as an alternative, but rejected: this app's
own design already runs migrations as an explicit pre-deploy step in production
(`spring.flyway.enabled=false` by default under the `prod` profile — `application-prod.properties`,
SYSTEM 03 TASK 3.2) and already hard-refuses to boot at all if the schema is behind what the build
expects (`StartupSchemaVersionCheck`, `@PostConstruct`). Given that, "is Flyway healthy" has no
runtime signal to report that can change between boot and shutdown the way a live Redis/DB
connection can — the boot-time gate is already the correct enforcement point. Full reasoning is
also inline as a comment on the property in `application.properties`.

## TASK 5.3 — Graceful shutdown [DONE]

`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s` in
`application.properties` — on SIGTERM, Tomcat stops accepting new requests but lets in-flight ones
finish first.

`AsyncConfig`'s three `ThreadPoolTaskExecutor`s (`taskExecutor`/agent, `fileProcessingExecutor`,
`reportingTaskExecutor`) previously called neither `setWaitForTasksToCompleteOnShutdown` nor
`setAwaitTerminationSeconds` — an in-flight file import or report generation was simply abandoned
on shutdown instead of being allowed to finish, exactly the gap the task describes. Both are now
set, with per-executor timeouts: 60s for `fileProcessingExecutor`/`reportingTaskExecutor` (batch
work worth waiting longer for), 30s for the agent executor (Lucien's conversational tool-loop work
is less critical to complete every mid-flight tool call for).

Separately, `spring.task.scheduling.pool.size` was never set anywhere (confirmed via grep across
every `application*.properties` before this session) — Spring Boot's default is `1`, meaning all
15 `@Scheduled` jobs in this app (`HikariPoolExhaustionMonitor`, `PartitionMaintenanceJob`,
`DunningScheduler`, `MaintenanceScheduler`, `VisitApprovalScheduler`, `UnassignedCaseScheduler`,
`SettlementOfferScheduler`, `ReconciliationScheduler`, `ReconciliationEodScheduler`,
`PtpScheduler`, `MonthlySnapshotScheduler`, `MisEodScheduler`, `WebSocketHeartbeatManager`,
`LucienSessionTtlJob`, `PingTtlScheduler`) were sharing one single thread — a slow batch job could
starve a time-sensitive one system-wide. Set to `8`.

`spring.task.scheduling.shutdown.await-termination=true` (+ `await-termination-period=30s`) — the
default (`false`) does not wait for an in-flight `@Scheduled` run on shutdown, meaning a job killed
mid-execution leaves its ShedLock lock held until `ShedLockConfig`'s `defaultLockAtMostFor=PT10M`
safety-net TTL expires. That TTL is still the correct backstop for an *ungraceful* crash (kept
as-is) — this change only fixes the *graceful*-SIGTERM case, where there's no reason not to just
wait for the job to finish and release its lock cleanly.

## Verification

**Property names checked against the actual dependency jars for this exact Spring Boot version
(3.4.13)**, not assumed from memory — the Flyway mistake above is exactly the failure mode this
guards against:
- `spring.task.scheduling.pool.size`, `spring.task.scheduling.shutdown.await-termination`,
  `spring.task.scheduling.shutdown.await-termination-period`, `server.shutdown`,
  `spring.lifecycle.timeout-per-shutdown-phase` — all confirmed present in
  `spring-boot-autoconfigure-3.4.13.jar`'s `META-INF/spring-configuration-metadata.json`.
- `redis`/`db` health contributors confirmed via `RedisHealthContributorAutoConfiguration` /
  `DataSourceHealthContributorAutoConfiguration` both being real autoconfiguration entries in
  `spring-boot-actuator-autoconfigure-3.4.13.jar` (and empirically: the app boots and
  `/actuator/health/readiness` returns `{"status":"UP"}` with both present).

**Full `mvn -f server/pom.xml test`: 654 tests, 0 failures, 0 errors, 2 skipped** (the same two
`StartupSchemaVersionCheckTest` cases every prior system this session has hit — needs `CREATE
DATABASE` privilege not present for the local `opstool` role), **BUILD SUCCESS**. Run twice: once
with `flyway` still in the readiness group (caught the bug — ~40 test classes failed with
`NoSuchHealthContributorException`), once after removing it (clean pass, the number above).

**Real boot test**: packaged jar (`mvn -DskipTests package`), `SPRING_PROFILES_ACTIVE=prod`,
`TRUSTED_PROXY_CIDR=172.28.0.0/16` (this literal value was used for this boot test only, before
SYSTEM 07 TASK 7.1 discovered it's invalid as the real Tomcat regex this property actually needs —
see `docs/CONFIG-REFERENCE.md`'s correction; harmless for this specific test since the request
came directly from loopback with no forwarded headers to validate), against real local
Postgres/Redis. Booted cleanly; `/actuator/health/liveness` and `/actuator/health/readiness` both
returned `200 {"status":"UP"}`. Process stopped after confirming (not left running).

**Not verified, flagged rather than silently assumed**:
- **Readiness flips to `503`/`OUT_OF_SERVICE` when Redis is down, while liveness stays `200`.**
  Intended to test this by stopping the local Redis instance, but that's the user's own local dev
  Redis service — stopping/killing it was not something to do without asking, and the check was
  deferred rather than forced through. The underlying mechanism (Spring Boot's readiness group
  reflecting a down `RedisHealthIndicator`) is standard, well-documented behavior, but the actual
  runtime flip was not observed end-to-end this session.
- **Graceful shutdown actually waits for an in-flight async/scheduled task to finish.** Windows has
  no clean way to deliver a real SIGTERM-equivalent to a plain console Java process from this
  environment — `taskkill` without `/F` was refused ("can only be terminated forcefully"), and
  `/F` is a hard kill, not a graceful one, so it wouldn't have tested anything. The configuration
  is standard Spring Boot (`server.shutdown=graceful`,
  `ThreadPoolTaskExecutor#setWaitForTasksToCompleteOnShutdown`,
  `spring.task.scheduling.shutdown.await-termination`), all property/method names version-checked
  per above, but the actual shutdown-sequence behavior is unverified here. **Re-verify both of
  these on a real Linux host (or CI) before relying on them in production** — e.g. `kill -TERM
  <pid>` while an in-flight file import is running, and `docker stop`/`systemctl stop redis` for
  the readiness flip.
