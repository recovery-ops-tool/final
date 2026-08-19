# Infrastructure — current state and decisions

Pre-populated 2026-08-18 to answer SYSTEM 05 TASK 5.1's "confirm and document the deployment
target" investigation, ahead of that system's own execution session — decisions below came
directly from the user, not from re-deriving them via a fresh SYSTEM 05 investigation.

## What exists in the repo today

- `docker-compose.yml`, `server/Dockerfile`, `web/Dockerfile` at the repo root — a Docker-Compose
  based deployment, not Kubernetes/ECS/etc.
- No Terraform/IaC (`*.tf`) found.
- `.github/workflows/` has 3 files (`server-ci.yml`, `web-ci.yml`, `mobile-ci.yml`) — existence
  confirmed during SYSTEM 05 TASK 5.1; their actual content/coverage is still SYSTEM 06's job,
  not re-verified here.
- `Caddyfile` (reverse proxy / TLS termination via Caddy, per
  `docs/superpowers/plans/2026-08-10-lucien-ambient-voice.md`). **Still has a literal unfilled
  placeholder domain (`yourdomain.com`)** — must be replaced with the real OCI deployment's
  domain before that deploy, otherwise Caddy can't get a matching TLS cert.
- `tts-service/` (FastAPI, IndicF5 TTS + faster-whisper STT) exists in the repo but has **no
  service block in `docker-compose.yml` at all** — it is not wired into the deployment. If
  anything ever relies on `TTS_BASE_URL`'s `localhost:8100` default in a deployed container, that
  resolves to the backend container's own loopback, not tts-service, since there's no compose
  service to route to. This is tangled up with the still-open "AI backend" decision below (Sarvam
  AI direction may make tts-service moot for TTS) — not silently wired in here; flag/ask before
  adding it to compose.
- Real request chain (relevant to `TRUSTED_PROXY_CIDR`, SYSTEM 04's still-open item): **two
  hops**. Caddy (`reverse_proxy web:80`, TLS termination) → nginx inside the `web` container
  (`proxy_pass http://backend:8080` for `/api/` and `/ws/`, sets `X-Forwarded-For`) → Spring Boot
  `backend`. As of SYSTEM 05 TASK 5.1, `docker-compose.yml` now pins an explicit bridge subnet
  (`172.28.0.0/16`, top-level `networks.default.ipam.config`) instead of relying on Docker's
  auto-assigned (and not-stable-across-recreations) subnet.
  **`TRUSTED_PROXY_CIDR` should be set to `172\.28\.\d{1,3}\.\d{1,3}` in production — a regex, not
  the CIDR string `172.28.0.0/16` despite the variable's name.** Tomcat's `RemoteIpValve` compiles
  this value with `Pattern.compile(...)`; the literal CIDR string would never match any real IP
  (found and fixed during SYSTEM 07 TASK 7.1 — see `docs/CONFIG-REFERENCE.md`'s entry for the full
  explanation, including how it was confirmed via bytecode).

## Decisions (user-confirmed)

**Deployment target: OCI (Oracle Cloud Infrastructure).**
Backend, frontend, Postgres, and Redis run on an OCI Ampere A1 instance (2 OCPU / 12GB — the
free-tier shape as of the June 2026 cut; do not provision the older 4 OCPU/24GB shape). DNS via
Cloudflare, SSL/TLS mode must be **Full (strict)** — Flexible causes a redirect loop with Caddy.
Step-by-step provisioning (instance creation, static IP, security list, Docker install, deploy) is
already written out in `docs/superpowers/plans/2026-08-10-lucien-ambient-voice.md`, Task 3 — that
task is deployment-target-generic despite living in a Lucien-specific plan file and should be the
reference for SYSTEM 05 TASK 5.1/5.2's actual provisioning work, not re-derived from scratch.

**AI backend: Sarvam AI, via hosted API — not self-deployed.**
Supersedes an earlier plan (see the amendment at the top of
`docs/superpowers/plans/2026-08-10-lucien-ambient-voice.md`) that would have moved Lucien's LLM
from local CPU Ollama to a self-hosted RunPod GPU Serverless endpoint. That RunPod work is now
unnecessary — Lucien's `ModelClientPort` interface
(`server/src/main/java/com/recoverpro/server/port/ModelClientPort.java`) already exists as the
swap point (currently only implemented by `LlamaClientAdapter`, which its own javadoc already
flags as swappable); a new adapter calling Sarvam AI's API replaces it. **Not yet designed**: the
actual request/response mapping (needs Sarvam AI's API docs), which Sarvam AI product(s) are in
scope (chat/LLM only, or also the STT/TTS the same plan's "Existing voice POC" section describes
as `tts-service/`'s IndicF5 + faster-whisper, currently CPU-only and "not viable for real usage"),
and credential storage (fits SYSTEM 04's `docs/RUNBOOK-SECRETS.md` pattern once that exists).

## SYSTEM 05 execution (TASK 5.2 / 5.3, done 2026-08-18)

- **Liveness/readiness probes**: `management.endpoint.health.probes.enabled=true` +
  `livenessState`/`readinessState` enabled in `server/src/main/resources/application.properties`,
  exposing `/actuator/health/liveness` and `/actuator/health/readiness`. Readiness group extended
  beyond the default (`readinessState` alone) to `readinessState,redis,db`. Point a load
  balancer's liveness check at `.../liveness` and its readiness check at `.../readiness` — not
  both at the aggregate `/actuator/health`, which conflates "alive" with "should receive traffic."
  `server/Dockerfile`'s `HEALTHCHECK` now hits `.../readiness` for the same reason.
  **Prerequisite bug fixed first**: `SecurityConfig.PUBLIC_PATHS` had the exact string
  `"/actuator/health"` (no `/**`), which would have 401'd both new probe sub-paths — changed to
  `"/actuator/health/**"`.
  **`flyway` deliberately left out of the readiness group**, despite the task's original wording
  ("Redis and Flyway") — Spring Boot 3.4.13 ships no Flyway health contributor at all (verified
  directly against the actuator-autoconfigure jar's autoconfiguration imports: only
  `FlywayEndpointAutoConfiguration`, the `/actuator/flyway` info endpoint, exists). Naming a
  nonexistent contributor doesn't degrade gracefully -- Spring Boot's group-membership validation
  refuses to let the `ApplicationContext` start at all, which was caught by a real `mvn test` run
  (nearly every integration test failed with `NoSuchHealthContributorException` until this was
  fixed) and would have broken production boot outright, not just the probe. See the property's
  own comment in `application.properties` for the full reasoning, including why a hand-rolled
  Flyway `HealthIndicator` wasn't worth building given this app's migrations-are-an-explicit-
  pre-deploy-step design.
- **Graceful shutdown**: `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`.
  `AsyncConfig`'s three executors (`taskExecutor`/agent, `fileProcessingExecutor`,
  `reportingTaskExecutor`) now set `setWaitForTasksToCompleteOnShutdown(true)` with per-executor
  await timeouts (30s agent, 60s file/report — batch work is worth waiting longer for than a
  conversational Lucien tool call). `spring.task.scheduling.pool.size` set to 8 (was defaulting
  to Spring Boot's `1`, meaning all 15 `@Scheduled` jobs shared one thread — a slow one could
  starve a time-sensitive one like `WebSocketHeartbeatManager`).
  `spring.task.scheduling.shutdown.await-termination=true` (+ 30s period) so an in-flight
  `@Scheduled` job releases its ShedLock lock cleanly on SIGTERM instead of sitting until
  `ShedLockConfig`'s `defaultLockAtMostFor=PT10M` safety-net TTL expires (that TTL is still the
  correct backstop for an ungraceful crash — this just fixes the graceful-shutdown case).
- Verified locally: `mvn test` passes (654+ tests, zero context-load failures — this run is what
  actually caught the Flyway readiness-group bug above); a packaged jar boots cleanly under the
  `prod` profile against real local Postgres/Redis, with `/actuator/health/liveness` and
  `.../readiness` both returning `200 {"status":"UP"}`. **Not verified**: the readiness-flips-
  to-503-when-Redis-is-down behavior (the local Redis instance is the user's own dev service;
  stopping it was declined rather than force-killed) and the graceful-shutdown-completes-an-
  in-flight-task behavior (Windows has no clean way to send a real SIGTERM-equivalent to a
  console Java process from this environment — `taskkill` without `/F` refused, and `/F` is a
  hard kill, not a graceful one). Both rely on standard, version-confirmed Spring Boot
  configuration keys (checked directly against `spring-boot-autoconfigure-3.4.13.jar`'s
  configuration metadata: `spring.task.scheduling.pool.size`,
  `spring.task.scheduling.shutdown.await-termination[-period]`, `server.shutdown`,
  `spring.lifecycle.timeout-per-shutdown-phase` all exist as real properties in this exact
  version) and `ThreadPoolTaskExecutor` methods, but the actual runtime behavior under those two
  specific scenarios is unverified — a real pre-production check (e.g. on a Linux host, or CI)
  should confirm both before relying on them. See `docs/SYSTEM-05-INFRASTRUCTURE.md`.

## Open questions for a future SYSTEM 05 (or Lucien-provider-migration) session

- Exact Sarvam AI product scope (chat / STT / TTS / all three).
- Sarvam AI credential provisioning and rotation procedure.
- Whether OCI Ampere A1 free tier (2 OCPU/12GB) is sufficient once Lucien's inference calls are
  network round-trips to Sarvam AI rather than local Ollama — likely yes (removes local LLM CPU
  load entirely), but not load-tested.
- CI/CD pipeline existence (SYSTEM 06's own job, not re-verified here).
