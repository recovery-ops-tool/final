# SYSTEM 09 — Authorization / RBAC: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 09 block, across two sessions
(2026-08-18 and 2026-08-19). TASK 9.1 and 9.2 were completed and tested in the first session;
TASK 9.3 was left ~55% done at a deliberate, verified checkpoint (24/43 files); this record covers
that checkpoint plus finishing 9.3 and all of 9.4 in the second session.

## TASK 9.1 — Eliminate discarded-return guards [DONE]

Grepped every `belongsToOrg` call site (`server/src/main/java`, ~60 hits). Every
`service/impl/*.java` call site already branched on the boolean correctly. The real bug was in
**10 Lucien tool implementations**
(`server/src/main/java/com/recoverpro/server/lucien/tool/impl/`: CheckCallingHoursTool,
CreatePtpTool, DispatchCaseTool, GetCasePtpStatusTool, GetMyAssignmentsTool, GetVisitHistoryTool,
LogVisitOutcomeTool, ReassignCaseTool, SendNotificationTool, SubmitVisitInterviewTool) — all called
`orgIsolationGuard.belongsToOrg(...)` as a bare statement, discarding the boolean result entirely;
pure dead code enforcing nothing. The task's own text only flagged 1 of these (CreatePtpTool) as
"confirmed," implying the other 9 weren't caught before this pass.

Traced the full Lucien tool-execution call chain (`LucienController` → `LucienServiceImpl.chat()`
→ `LucienAgentLoop.run()` → `ToolExecutor.execute()` → `tool.execute(args, principal)`) to confirm
it's fully synchronous (no `@Async` boundary), so `SecurityContextHolder` stays correctly populated
throughout — safe to make the check actually enforce, not just a theoretical fix.

Added `OrgIsolationGuard.assertBelongsToOrg(UUID)` (throws `AccessDeniedException`), converted all
10 call sites to it. New `OrgIsolationGuardTest` (4 tests: cross-org throws, same-org passes,
platform-admin bypasses, unauthenticated throws).

**ACCEPTANCE**: met — no call site discards a `belongsToOrg()` result anymore; the cross-org test
throws `AccessDeniedException` (`OrgIsolationGuardTest.assertBelongsToOrg_callerInDifferentOrg_throwsAccessDenied`).

## TASK 9.2 — Endpoint authorization coverage test [DONE]

Wrote `EndpointAuthorizationCoverageTest`
(`server/src/test/java/com/recoverpro/server/security/`) — enumerates every Spring MVC handler via
`RequestMappingHandlerMapping` (needed `@Qualifier("requestMappingHandlerMapping")`, since
Actuator's `controllerEndpointHandlerMapping` is a second bean of that type; needed
`webEnvironment = MOCK`, not `NONE`, which doesn't register MVC infra at all), asserts each has
`@PreAuthorize` (method or class) or is in an explicit `PUBLIC_ENDPOINTS` allowlist identified by
`SimpleClassName#methodName` (not path — survives a path rename).

First real run surfaced **17 genuine violations**:
- 6 framework-provided endpoints (Spring Boot's `BasicErrorController`, springdoc's Swagger/OpenAPI
  endpoints) — allowlisted with justification; an auth failure can't itself require auth to render
  its error page.
- 2 webhook controllers had allowlist entries with the wrong guessed method name (`handleWebhook`
  vs actual `handle`) — fixed.
- **9 real gaps**: `VisitSessionController` had zero `@PreAuthorize` anywhere in the class (8
  self-service methods unguarded); `AttendanceController#checkIn`/`getMyAttendance`,
  `OrganizationController#me`, `DailyDispatchController#myList`, `UserController#getMyProfile` each
  the one unguarded method in an otherwise-fully-guarded controller. Not a live vulnerability —
  `SecurityConfig`'s filter chain already requires a valid JWT for all these paths — but zero
  role-level enforcement was explicit, violating deny-by-default. Fixed with
  `@PreAuthorize("isAuthenticated()")` (class-level where every unguarded method needed it
  uniformly; method-level for the single-gap classes).

**ACCEPTANCE**: met — `EndpointAuthorizationCoverageTest` passes (verified again after TASK 9.3's
constant substitutions, still passes — the refactor didn't drop coverage on any endpoint).

## TASK 9.3 — Centralize permission constants [DONE]

Grepping the task's own literal pattern (`hasAnyRole('PLATFORM_ADMIN'`) undersold the real scope —
the same duplicated role-sets also appeared with roles in a **different order** (e.g.
`hasAnyRole('ORG_ADMIN','PLATFORM_ADMIN')` vs `'PLATFORM_ADMIN','ORG_ADMIN'`) and with an
**accidental self-duplicate role** within one expression (e.g. `...,'TL','ORG_ADMIN'` — ORG_ADMIN
twice) — semantically identical duplicates a literal grep wouldn't catch.

Created `server/src/main/java/com/recoverpro/server/security/Authz.java` with 8 canonical
constants: `ADMINS`, `LEADS`, `MANAGERS_AND_ABOVE`, `LEADS_AND_FO`, `ALL_STAFF`,
`ALL_STAFF_NO_TRACER`, `ADMINS_OR_ROLE_ASSIGN`, and `FO_AND_ADMINS` (added mid-task — the
`{FO, ORG_ADMIN, PLATFORM_ADMIN}` set turned out to be duplicated across `AgentFieldController` (8x)
and `DocumentController` (2x, with an accidental self-duplicate `ORG_ADMIN`), which the original 7
constants had no slot for).

Design choice: controllers keep their own locally-named constant where one already existed (e.g.
`private static final String READERS = Authz.LEADS;`) rather than switching every
`@PreAuthorize(READERS)` call site to `@PreAuthorize(Authz.LEADS)` directly — same deduplication
result (the literal string exists exactly once, in `Authz.java`), zero risk of a typo while
rewriting ~90 individual annotation sites, and the local name stays more readable in context.

**43 controllers fixed total** (all via individual `Edit` calls, never a bulk/scripted pass — see
[[feedback_no_bulk_scripted_edits]]): the 24 from the first session (AllocationController,
AssignmentController, AuditLogController, CalendarController, CallLogController, CasesController,
CollectionController, ColumnSchemaController, DailyDispatchController,
FieldAgentDashboardController, FileUploadController, GrievanceController, GrievanceOfficerController,
KfsController, MessageTemplateController, NonContactableController, NpaController, PtpController,
ReportingController, RestructureProposalController, RoleController, SettlementOfferController,
UserController, VisitLogController) plus 19 more this session: `admin/FeatureFlagAdminController`,
`AgentFieldController`, `AllocationOptimizerController`, `AttendanceController`, `BorrowerController`,
`CallLogController` (one more inline literal beyond the earlier fix — a genuine catch: it read
`ORG_ADMIN,MANAGER,TL,PLATFORM_ADMIN`, i.e. `LEADS`, not the file's existing `ALL_STAFF` local
constant — using the wrong existing constant here would have silently widened a recording-download
endpoint from leads-only to every staff role), `DocumentController`, `ExportController`,
`FraudCaseController`, `KpiController`, `OrganizationController`, `PaymentController`,
`PermissionController`, `ReconciliationController`, `RiskScoringController`,
`SubscriptionController`, `UploadDataController`, `UserCreationRequestController`,
`VisitSessionController`.

One deliberate exception left untouched: `DocumentController`'s `hasAnyRole('FO', 'ORG_ADMIN')`
(no PLATFORM_ADMIN) — a genuinely different 2-role set, not matched by the task's own acceptance
grep (doesn't start with `PLATFORM_ADMIN` or `ORG_ADMIN`), and not duplicated anywhere else, so it
doesn't earn a ninth canonical constant.

**ACCEPTANCE**: met —
`grep -rn "hasAnyRole('PLATFORM_ADMIN'\|hasAnyRole('ORG_ADMIN" server/src/main/java --include=*.java`
now returns hits only in `Authz.java` itself (confirmed); full suite green (678 tests, 0 failures,
0 errors — see Verification below).

## TASK 9.4 — Audit access denials [DONE]

### 9.4.a — verify RestAccessDeniedHandler records ACCESS_DENIED with the attempted resource

Confirmed true, and pinned down with a real test rather than just re-reading the code:
`RestAccessDeniedHandlerTest` drives the actual handler with a real `AccessDeniedException`,
asserts the 403 body, and verifies the denial is handed to `AccessDenialAuditor` with the caller's
id; `AccessDenialAuditorTest.recordAccessDenied_recordsAccessDeniedWithAttemptedResourceAndDeniedResult`
verifies the resulting `AuditEventRequest` carries `action=ACCESS_DENIED`, `result=DENIED`, and the
request path/method in `metadata` — the "attempted resource."

### 9.4.b — confirm 401 vs 403 are distinguishable in the audit trail

**Not true when this task started** — this was the real finding. `RestAuthenticationEntryPoint`
(the 401 handler) took no `AuditService` at all and wrote nothing to `unified_audit_events`; it was
constructed with a bare `new RestAuthenticationEntryPoint()` in `SecurityConfig`. Worse,
`JwtAuthenticationFilter` has three more 401 paths that short-circuit *before* Spring Security's
exception translation ever runs (blacklisted-token replay, an invalid/expired SSE stream ticket,
and a valid JWT for a since-deleted user) — none of those reached the entry point either, so they
were equally invisible. Net effect: every 403 was audited, and **zero 401s were audited, from any
of the four paths that produce one** — not "hard to distinguish," but completely absent from the
trail. This is exactly the traffic shape (unauthenticated requests, no rate limiting ahead of this
layer) an attacker's credential-guessing or token-replay traffic would produce.

Fixed:
- Added `AuditAction.AUTH_UNAUTHORIZED` (WARNING severity, same as `ACCESS_DENIED`) — a distinct
  action so a query can tell "no credentials at all" apart from "credentials rejected by role."
- Added `AuditActorType.ANONYMOUS` — `SYSTEM` already means "this app itself acted" (a job, a
  webhook handler); an unidentified external caller needed its own label, not a reuse of that one.
- Migration `V098__unified_audit_events_auth_unauthorized.sql` extends both CHECK constraints on
  `unified_audit_events` (`action`, `actor_type`) to allow the new values — confirmed applying
  cleanly against the real partitioned table on local Postgres 16 (`StartupSchemaVersionCheck`
  logs `Database schema version: 098` in every subsequent test run).
- `RestAuthenticationEntryPoint` now takes an `AccessDenialAuditor` and records
  `AUTH_UNAUTHORIZED` with `actorTypeOverride=ANONYMOUS`.
- `JwtAuthenticationFilter`'s three short-circuit rejections (`writeUnauthorized` call sites) now
  do the same, each with a `reason` in metadata identifying which sub-case it was ("Token has been
  revoked", "Invalid credentials", "Invalid or expired stream ticket").
- `SecurityConfig` wiring updated: both handlers and the filter now receive `AccessDenialAuditor`
  (a new `@Component`) instead of `AuditService` directly.

**ACCEPTANCE** (extended beyond the literal wording, since "confirm" turned out to require a fix
first): met — `AccessDenialAuditorTest.accessDeniedAndUnauthorized_useDifferentAuditActions` proves
the two paths land on different `AuditAction` values from the one shared recording call site;
`RestAuthenticationEntryPointTest` and the new `JwtAuthenticationFilterUnauthorizedAuditTest` (3
cases: blacklisted token, deleted-user token, invalid SSE ticket) prove all four 401 paths now
reach it.

### 9.4.c — denial-storm coalescing

**Decision: implemented, not just documented.** Every `AuditService.record()` call is its own
`REQUIRES_NEW` transaction (a fresh connection + row write). 401s in particular have no rate
limiting anywhere ahead of them in the filter chain (unlike login, which SYSTEM 07 TASK 7.3 already
throttles) — a scanner hammering any protected endpoint with no/bad tokens would, without this,
turn into one audit write per request on the exact traffic pattern most likely to be high-volume.
That's a self-inflicted load/DoS risk sitting on top of the security question, not just table
bloat.

Added `DenialAuditThrottle` (`server/src/main/java/com/recoverpro/server/security/`): coalesces by
`(actorKey, resourceKey)` into a 60-second window using a Redis `SETNX`-with-TTL
(`opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(60))`) — only the first denial for a given
pair in the window is recorded. `actorKey` is the caller's user id for 403s (falls back to remote
IP if the principal somehow isn't resolvable) and the remote IP for 401s (no identity exists yet).
`resourceKey` is the raw `METHOD path` — deliberately coarse (two different borrower ids under the
same route count as two keys, not one); the goal is catching the attack shape (one endpoint
hammered), not a perf-analytics rollup.

**Fails OPEN, not closed**, on a Redis error — the opposite of the JWT blacklist check's fail-closed
choice. Losing the audit trail during a Redis outage is worse than a few extra writes; that's also
exactly the moment a real attack is most likely to be happening unwatched. `DenialAuditThrottleTest`
covers first-hit-records, repeat-hit-suppressed, different-actors-both-count-as-first-hit, and the
fail-open behavior explicitly.

Both `RestAccessDeniedHandler` (403) and `RestAuthenticationEntryPoint`/`JwtAuthenticationFilter`
(401) share this gate through the same `AccessDenialAuditor` component, so the coalescing policy is
one piece of logic, not four copies that could drift.

**ACCEPTANCE**: met — "hitting an endpoint without permission produces exactly one ACCESS_DENIED
audit row naming the endpoint" holds for a single hit (the throttle's first-in-window case);
repeated hits within 60s now produce exactly one row total, not one per request, which is the
storm-coalescing behavior 9.4.c asked for a decision on.

## New/changed files this session

- `server/src/main/java/com/recoverpro/server/security/Authz.java` — added `FO_AND_ADMINS`.
- `server/src/main/java/com/recoverpro/server/security/DenialAuditThrottle.java` (new)
- `server/src/main/java/com/recoverpro/server/security/AccessDenialAuditor.java` (new)
- `server/src/main/java/com/recoverpro/server/security/RestAccessDeniedHandler.java` (refactored
  onto `AccessDenialAuditor`)
- `server/src/main/java/com/recoverpro/server/security/RestAuthenticationEntryPoint.java` (now
  audits)
- `server/src/main/java/com/recoverpro/server/security/jwt/JwtAuthenticationFilter.java` (now
  audits its 3 short-circuit rejections)
- `server/src/main/java/com/recoverpro/server/config/SecurityConfig.java` (wiring)
- `server/src/main/java/com/recoverpro/server/enums/AuditAction.java` — added `AUTH_UNAUTHORIZED`
- `server/src/main/java/com/recoverpro/server/enums/AuditActorType.java` — added `ANONYMOUS`
- `server/src/main/resources/db/migration/V098__unified_audit_events_auth_unauthorized.sql` (new)
- 19 controllers (TASK 9.3, listed above)
- New tests: `DenialAuditThrottleTest`, `AccessDenialAuditorTest`, `RestAccessDeniedHandlerTest`,
  `RestAuthenticationEntryPointTest`, `JwtAuthenticationFilterUnauthorizedAuditTest`
- Updated test: `JwtAuthenticationFilterBlacklistFailClosedTest` (constructor signature)

## Verification

Full `mvn -f server/pom.xml test`: **678 tests, 0 failures, 0 errors, 2 skipped** (up from the
663-test checkpoint the second session resumed from; +15 new tests, no regressions). Migration V098
confirmed applying cleanly against real local Postgres 16 in every test run since
(`StartupSchemaVersionCheck` logs schema version 098).

The tasklist's own specified command,
`mvn -f server/pom.xml test -Dtest='*Authz*Test,*Permission*Test,*Rbac*Test'`, matches **zero**
actual test classes in this codebase (none of SYSTEM 09's real test names — `OrgIsolationGuardTest`,
`EndpointAuthorizationCoverageTest`, `DenialAuditThrottleTest`, `AccessDenialAuditorTest`,
`RestAccessDeniedHandlerTest`, `RestAuthenticationEntryPointTest`,
`JwtAuthenticationFilterUnauthorizedAuditTest` — contain "Authz", "Permission", or "Rbac") and fails
outright (`No tests matching pattern ... were executed!`) rather than silently passing. Ran the
real set instead: `mvn -f server/pom.xml test -Dtest='OrgIsolationGuardTest,EndpointAuthorizationCoverageTest,DenialAuditThrottleTest,AccessDenialAuditorTest,RestAccessDeniedHandlerTest,RestAuthenticationEntryPointTest,JwtAuthenticationFilterUnauthorizedAuditTest,JwtAuthenticationFilterBlacklistFailClosedTest'`
— 21/21 passed.

No live-instance verification attempted (matches the resource-conscious approach adopted after
SYSTEM 07: targeted test classes while iterating, one full-suite run at the checkpoint, no app
boots) — the acceptance evidence above is all test-level, not against a running server.

All 4 tasks (9.1, 9.2, 9.3, 9.4) are done. **Rollup checkbox: [x].**
