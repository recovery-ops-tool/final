# SYSTEM 13 — Error Tracking: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 13 block, 2026-08-19 session — third system
this session, straight after SYSTEM 09 and SYSTEM 12, continuing priority order among the
untouched P0 systems.

## Prerequisite gap found and fixed first

SYSTEM 13's own PREREQUISITES line says "SYSTEM 11 (MDC context) done." SYSTEM 11 itself is
untouched (0/3 tasks) — but its CURRENT STATE note claims requestId/orgId/userId are "already"
in MDC. Checked the actual code before trusting that: **only requestId was ever in MDC, and under
the wrong key** (`RequestLoggingFilter.MDC_KEY = "reqId"`, but `logback-spring.xml`'s prod JSON
encoder — which already existed, pre-dating this session — has always requested `"requestId"`
via `includeMdcKeyName`). Every prod JSON log line has been missing its request-correlation field
since that encoder was written. orgId/userId were never in MDC at all; `RlsContextFilter` only
ever set `RlsOrgIdHolder` (a separate ThreadLocal for RLS), not MDC.

Fixed both, scoped narrowly (just the MDC wiring SYSTEM 13 actually needs, not SYSTEM 11's full
TASK 11.1 — the JSON-encoder/logback work is already done, that part just had this one bug):
- `RequestLoggingFilter.MDC_KEY`: `"reqId"` → `"requestId"`. Safe rename — the only other
  reference in the codebase (`AuditServiceImpl`) reads the constant symbolically, not the literal
  string.
- `RlsContextFilter`: now also puts `orgId`/`userId` into MDC (cleared in the same `finally` as
  the existing `RlsOrgIdHolder.clear()`). Both ride `AsyncConfig.mdcPropagatingDecorator()`'s
  existing whole-map-copy propagation with no changes needed there.

**Test**: `RlsContextFilterTest` (new, 4 cases: sets both keys during the request, clears both
after, clears both even when downstream throws, never sets them when unauthenticated).

**Regression caught and fixed before it shipped**: the userId addition initially called
`principal.getId().toString()` unguarded (unlike the orgId branch, which already null-checked).
A real full-suite run caught this immediately — `RlsBypassBlastRadiusTest` (a SYSTEM 01
pre-existing test using a partially-stubbed `UserPrincipal` mock) NPE'd on it. Null-guarded to
match the orgId branch; re-ran both the previously-failing test and the new one, both green.

## TASK 13.1 — Integrate an error tracker [DONE, live-verification deferred]

**Sentry** (the task's own suggestion), `sentry-spring-boot-starter-jakarta` 8.53.0 — the Jakarta
variant, since this app is Spring Boot 3/`jakarta.*`, not the plain starter which targets Boot 2.
Every non-obvious integration point was verified by decompiling the actual jars (same rigor as
SYSTEM 12's Prometheus-property investigation), not assumed from documentation:

- **Disabled by default**: `sentry.dsn=${SENTRY_DSN:}` — empty DSN, same pattern as every other
  third-party credential in this app.
- **Context attachment (13.1.b)**: `sentry.context-tags=requestId,orgId,userId` — confirmed via
  bytecode that `ContextTagsEventProcessor` (auto-registered whenever `MDC` is on the classpath,
  which it always is) reads exactly these MDC keys and attaches them as searchable Sentry tags on
  every event, with zero custom capture code needed for this half of 13.1.b.
- **Release/git SHA (13.1.b)**: needs no config at all — confirmed via bytecode that
  `sentry.use-git-commit-id-as-release` (default `true`) makes `SentryAutoConfiguration` read the
  `GitProperties` bean SYSTEM 06 TASK 6.4.c's `git-commit-id-maven-plugin` already provides and
  set it as `sentry.release` automatically.
- **Scrubbing (13.1.c, marked CRITICAL in the tasklist)**: three independent layers, not one --
  `sentry.send-default-pii=false` (confirmed via bytecode that `SpringSecuritySentryUserProvider`
  checks this flag before ever reading the authenticated principal, so no username/email reaches
  any event this way); `sentry.max-request-body-size=none`; and `SentryConfig`'s
  `BeforeSendCallback` bean, the belt-and-suspenders layer for what those two properties don't
  reach — request headers/cookies/query string (nulled outright) and free-text exception/event
  messages (redacted via `DataSanitizer`, the same PII-pattern matcher SYSTEM 07-era code already
  trusts for Lucien's LLM output safety filtering, not a new unreviewed regex).
- **Sample rates (13.1.d)**: `sentry.sample-rate=1.0` (errors, adjustable via `SENTRY_SAMPLE_RATE`)
  and `sentry.traces-sample-rate=0.0` (performance tracing is a separate product/billing dimension
  from error tracking, deliberately left off — the task asked for error tracking, not APM).
- **Explicit capture wiring**: confirmed via `SentryExceptionResolver`'s own default
  `exception-resolver-order=1` that Sentry's automatic Spring MVC exception capture would likely
  never actually fire in this app — `GlobalExceptionHandler`'s `@RestControllerAdvice` (default
  precedence 0) wins the resolver-chain race for every exception type first. Wired
  `Sentry.captureException(ex)` explicitly into `GlobalExceptionHandler.handleGeneric()` (the
  catch-all `Exception.class` handler) instead of relying on the automatic path — deliberately
  NOT added to the other handlers, which are curated/expected business exceptions
  (`BusinessException`, `ResourceNotFoundException`, ...), not "the new exception type appearing
  after a deploy" this task exists to catch.

**Tests**: `SentryConfigTest` (6 cases, against the real `BeforeSendCallback` and real
`DataSanitizer` — request body/headers/cookies/query-string stripped, phone/email/PAN redacted
from exception and event messages, ordinary non-PII messages pass through unchanged, no-request
event doesn't throw). `GlobalExceptionHandlerTest` (3 cases, via `Mockito.mockStatic(Sentry.class)`
— the generic handler captures, the response body never echoes the real exception message or class
name, a curated `BusinessException` deliberately does NOT capture).

**Live verification deferred**: task's literal acceptance ("a deliberately thrown test exception
appears in the tracker with org id attached and no PII in the payload") needs a real Sentry
account/DSN, which doesn't exist in this environment — same situation and same resolution as
SYSTEM 07 TASK 7.2 (a dedicated real-mechanism unit test stands in for the live check). Whoever
sets `SENTRY_DSN` in production should do one manual smoke test (throw a test exception, confirm
it lands in the Sentry dashboard with `orgId`/`userId`/`requestId` tags and no raw PII) before
trusting this fully.

## TASK 13.2 — Exception handling audit [DONE]

Ran the task's own literal grep first; it returned nothing (too fragile a pattern for this
codebase's formatting). Did the real thing instead: found all 139 `catch (Exception` occurrences
across 89 files via a plain grep, then had a forked agent read every single one in full context
(all 89 files, not a sample) and classify each as fine (logs at WARN/ERROR with context, rethrows,
or routes through the established `OpsAlertService.alertJobFailure` pattern) or a problem. 133 of
139 were already fine. **6 real problems found and fixed**:

1. **`RefreshTokenRotationServiceImpl.blacklistToken` — security-critical.** Silently no-op'd on
   any Redis write failure; a logged-out access token would stay valid until its own natural
   expiry with zero trace anything went wrong. Fixed: logs at ERROR + alerts via
   `OpsAlertService` (added as a new dependency), does NOT rethrow (this method runs first inside
   `logout()`/`logoutAllDevices()`'s transaction — letting it propagate would roll back the more
   important durable revocation, the refresh-token DB row, and leave the user not logged out at
   all). Never logs the token itself. New test:
   `logout_blacklistWriteFails_stillRevokesRefreshTokenAndAlertsInsteadOfSwallowing`.
2. **`CallingHoursGuard.nextEligibleAt`** — a holiday-lookup failure silently defaulted to
   `holiday=false` (permissive: calling allowed), the risky direction, and inconsistent with the
   same class's `isAllowedFor` method, which already fails closed (deny) for the identical lookup.
   Fixed to fail closed (treat as a holiday, skip the day) with a WARN log. New test file
   `CallingHoursGuardTest` (2 cases, including one proving `nextEligibleAt` and `isAllowedFor` now
   agree on fail-closed behavior for the same failure).
3. **`PtpController.scopeToPrincipal`** — an allocation-lookup failure during org/FO scoping
   silently excluded the PTP from the list, indistinguishable from a legitimate cross-org filter.
   Added a WARN log with the ptp/allocation ids; behavior (still excludes) unchanged.
4. **`PartitionMaintenanceJob.createUpcomingPartitions`** — the per-partition exception was
   discarded (only a `table_month` string kept), so the final summary `IllegalStateException` had
   no cause chain at all. Not a fully silent swallow in practice (the inner `createPartition`
   already logs at ERROR + alerts per-partition with full context) but the summary exception
   itself was undebuggable in isolation. Fixed to keep the first real exception as the summary
   exception's cause.
5. **`AgentFieldServiceImpl` live-location relay** and **6. `LiveTrackWebSocketHandler.broadcast`**
   — both silently swallowed JSON-serialization/relay failures, inconsistent with the identical
   method in the sibling `SosAudioWebSocketHandler`, which already logs at WARN for the exact same
   failure. Both now match that established pattern.

**@ControllerAdvice shape (13.2.c/d)**: `GlobalExceptionHandler` already has a generic
`Exception.class` fallback (`handleGeneric`) returning a fixed `"An unexpected error occurred"`
message, never the real exception's message or class name — confirmed by test. Spring Boot's own
final-fallback `BasicErrorController` (reached if an exception somehow escapes even
`GlobalExceptionHandler`, e.g. from a Filter) has safe defaults too, confirmed against
`spring-boot-autoconfigure-3.4.13.jar`'s real metadata: `server.error.include-stacktrace=never`,
`include-message=never`, `include-exception=false` — not assumed, checked directly given this
session's Prometheus-property incident already proved defaults are worth verifying, not assuming.

**Noted, not changed**: `GlobalExceptionHandler` returns a distinct `ErrorResponse` DTO
(`{status, error, message, path, timestamp, ...}`), not the app's `ApiResponse<T>`
(`{success, message, data}`) the task's own wording implies ("the app's standard ApiResponse error
shape"). Two different envelope shapes for success vs. error responses — `ErrorResponse` has no
`success` field at all. Flagged, not unified: changing the wire-format contract every frontend
fetch call depends on is a breaking API change outside a backend-only exception-handling audit's
scope, and `ErrorResponse` itself already satisfies the acceptance criterion's actual substance
(clean, structured, no stack trace).

**ACCEPTANCE**: met — no empty/silent catch blocks remain uninvestigated (all 139 reviewed); an
unhandled exception returns a clean, fixed-message error body with no stack trace or exception
class name (tested).

## TASK 13.3 — Frontend error capture [DONE, live-verification deferred]

Found substantially already built before touching anything: `@sentry/react` 10.68.0 already a
dependency, `Sentry.init()` already in `main.tsx` (disabled by default via `VITE_SENTRY_DSN`,
matching the backend's pattern independently), and a real `RouteErrorBoundary` already calling
`Sentry.captureException` on every React render crash. What was missing, and is what this task
actually asked for: scrubbing discipline (13.3.a's "same scrubbing discipline" as the backend) and
request-id correlation (13.3.b). Confirmed via `AskUserQuestion` before touching frontend logic
(per this project's standing UI/UX-only-scope rule) — user chose to complete it.

- **`web/src/utils/piiRedaction.ts`** (new): same PII pattern list as the backend's
  `PiiPatterns.java` (Indian mobile, Aadhaar, PAN, passport, email, card, account/IFSC), ported to
  TS since the two runtimes can't share code directly.
- **`main.tsx`**: added a `beforeSend` callback (confirmed the real type signature by inspecting
  `@sentry/core`'s actual `.d.ts` files, not assumed) that nulls `request.data/headers/cookies/
  query_string` and runs `stripPii` over the event message and every exception's `value`, mirroring
  the backend's `SentryConfig` layer-for-layer.
- **`axiosInstance.ts`**: every response (success and error) now tags Sentry's current scope with
  the backend's `X-Request-Id` response header (confirmed exported all the way through
  `@sentry/react` → `@sentry/browser` → `@sentry/core`). A coarse, global-scope-tag correlation
  (not per-request tracing spans) — good enough to answer "which request was in flight when this
  broke," which is the actual debugging question 13.3.b is after.

**Verification**: no frontend test runner exists in this project (checked `package.json`, no
vitest/jest) — verified via `tsc -b --noEmit` (clean, whole-project typecheck) and `eslint` on the
changed files (clean) instead. A full `npx vite build` was not run this session (declined). Live
verification (a deliberate frontend error actually appearing in a real Sentry dashboard, linked to
its backend request) deferred for the same reason as TASK 13.1 — no real DSN in this environment.

## New/changed files this session

- `server/.../config/RequestLoggingFilter.java` — MDC key rename (`reqId` → `requestId`).
- `server/.../filter/RlsContextFilter.java` — orgId/userId now in MDC.
- `server/pom.xml` — added `sentry-spring-boot-starter-jakarta`.
- `server/.../config/SentryConfig.java` (new) — scrubbing `BeforeSendCallback`.
- `server/.../application.properties` — Sentry config block.
- `server/.../common/exception/GlobalExceptionHandler.java` — explicit `Sentry.captureException`.
- `server/.../service/impl/RefreshTokenRotationServiceImpl.java` — security-critical fix.
- `server/.../service/CallingHoursGuard.java` — fail-closed consistency fix.
- `server/.../controller/PtpController.java` — added logging.
- `server/.../scheduler/PartitionMaintenanceJob.java` — cause-chaining fix.
- `server/.../service/impl/AgentFieldServiceImpl.java`,
  `server/.../websocket/LiveTrackWebSocketHandler.java` — added logging, matching sibling pattern.
- New tests: `RlsContextFilterTest`, `SentryConfigTest`, `GlobalExceptionHandlerTest`,
  `CallingHoursGuardTest`; extended `RefreshTokenRotationServiceImplTest`.
- `web/src/utils/piiRedaction.ts` (new), `web/src/main.tsx`, `web/src/api/axiosInstance.ts`.

## Verification

Full `mvn -f server/pom.xml clean test`: **703 tests, 0 failures, 0 errors, 2 skipped** (up from
the 687-test checkpoint SYSTEM 12 left this session at; +16 new tests, one regression caught and
fixed mid-session before it could ship — see the prerequisite-gap section above).

Frontend: `npx tsc -b --noEmit` clean, `npx eslint` clean on all changed files.

No live-instance/live-Sentry-dashboard verification (see each task's own section above for why,
and the SYSTEM 07 TASK 7.2 precedent this follows).

All 3 tasks (13.1, 13.2, 13.3) code-complete and tested. **Rollup checkbox: [x]**, with the
live-dashboard-verification caveat carried forward explicitly, matching SYSTEM 07's precedent.
