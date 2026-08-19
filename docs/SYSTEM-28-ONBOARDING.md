# SYSTEM 28 — Customer Onboarding: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 28 block, 2026-08-18 session.
Continuation of the same session that completed SYSTEM 35.

## The question you asked: revenue leak or broken orgs?

**Revenue leak, confirmed by code, not broken orgs.** Traced the actual gating path used by
real endpoints: `RequiresFeatureAspect` calls `FeatureFlagService.isEnabled(orgId, flagKey,
true)` — `defaultIfMissing=true`. A subscription-less org has no `FeatureFlag` rows at all
(nothing but a subscription event ever called `provisionFlagsFor`), so `LUCIEN_AI` and
`ADVANCED_REPORTS` both resolved to **allowed**. Separately, `EntitlementServiceImpl`'s
`canCreateUser`/`canCreateAllocations` treat `getLimit()` returning `Optional.empty()` (no limit
row, same root cause) as **unlimited**. So every subscription-less org has had free access to
every paid feature plus unlimited users and unlimited loans, indefinitely, since whenever the
bug was introduced.

**Correction to the tasklist's own SYSTEM 20 framing**: it describes "two parallel entitlement
systems" (`EntitlementService.hasFeature()` vs. `RequiresFeatureAspect`/`PlanFeatureMatrix`) as
independently-disagreeing data sources. In the actual code, both paths call the same
`FeatureFlagService.isEnabled()`/`getLimit()` against the same `FeatureFlag` rows — they're not
separate data sources. The real (smaller, but still real) inconsistency: the two call sites pass
different `defaultIfMissing` values (aspect: `true`; `EntitlementServiceImpl.hasFeature()`:
`false`), so the SAME missing-row case resolves differently depending which entry point is used.
Not touched this session — that convergence is SYSTEM 20's own TASK 20.1/20.3, out of scope here.

## Shared-file conflict found and resolved

TASK 28.1 requires editing `PlatformOrganizationController.create()` — but that file is listed
under `SHARED FILES — DO NOT EDIT CASUALLY (owned by this system)` under **SYSTEM 18**, not 28,
and the tasklist's global rule forbids editing an owned file from outside its owning block. This
is almost certainly *why* SYSTEM 18 is SYSTEM 28's stated prerequisite: SYSTEM 18's own TASK 18.1
(remove the client-supplied admin password, a real P0) touches the exact same method. Confirmed
with the user, who chose: do SYSTEM 18 TASK 18.1 first, then layer TASK 28.1 on top in the same
session. SYSTEM 18's other tasks (18.2 org lifecycle, 18.3 invite lifecycle, 18.4 GDPR deletion)
were **not** done — only 18.1, the piece blocking 28.1.

## SYSTEM 18 TASK 18.1 — Remove client-supplied initial password [DONE]

- `CreateOrganizationRequest` no longer has an `adminPassword` field at all (structural
  guarantee, not just "the endpoint ignores it").
- `PlatformOrganizationController.create()` now hashes `UUID.randomUUID() + UUID.randomUUID()`
  as the admin's throwaway initial credential — the exact pattern `UserServiceImpl.createUser()`
  already used for ordinary user invites (found and reused per TASK 18.1.d's own instruction,
  rather than inventing a second mechanism). The real welcome-OTP flow (`sendWelcomeOtp`) was
  already wired and unchanged.
- Frontend: removed the password/confirm-password fields from the platform-admin "New
  organization" form (`web/src/pages/PlatformSetupOrgModals.tsx`,
  `web/src/api/platformApi.ts`) — leaving them would have shown a working-looking password field
  that silently did nothing.
- Tests: `PlatformOrganizationControllerTest` — new coverage asserting the hashed input is
  genuinely random and non-repeating across two org creations.

## SYSTEM 28 TASK 28.1 — Every new org gets a subscription [DONE]

- `PlatformOrganizationController.create()` now creates an `OrgSubscription` (`TRIAL`,
  `STARTER`, `trialEndsAt = now + app.subscription.trial-days` [default 14]) in the **same**
  `@Transactional` method as the org and admin, then calls
  `featureFlagService.provisionFlagsFor(subscription)` so `FeatureFlag` rows exist immediately —
  closing the fail-open gap for every *new* org from the moment of creation. Audited with
  `SUBSCRIPTION_CREATED`.
- **Backfill** (28.1.d): `POST /api/v1/platform/subscriptions/backfill-missing`
  (`PlatformSubscriptionController`) — finds every tenant org with no `OrgSubscription` row and
  gives it the same defined TRIAL state, provisions flags, audits each. Admin-triggered (matching
  the existing `POST /backfill-invoices` convention in the same controller), **not yet run** —
  someone with platform-admin access needs to call it once to actually close the gap for
  pre-existing subscription-less orgs.
  **Business assumption flagged for sign-off**: backfilling to TRIAL grants existing
  (potentially long-running) subscription-less orgs a fresh trial window, rather than e.g.
  immediately requiring payment. This is the safest failure mode (no abrupt cutoff for an org
  that may have real users mid-shift) but is a revenue decision, not a purely technical one —
  confirm before running the backfill in production, or run it and immediately review which orgs
  it affected via the `SUBSCRIPTION_CREATED` audit trail (`reason` = "Backfill: org had no
  subscription row").
- **Trial-expiry bug found and fixed along the way**: `FeatureFlagService.effectivePlan()`
  mapped `TRIAL` straight to `STARTER` regardless of `trialEndsAt` — nothing anywhere checked
  whether a trial had actually expired. TASK 28.1's own acceptance criterion ("an expired trial
  cannot use paid features") could not be true without this fix, independent of TASK 28.2's
  scheduled job. Fixed: an expired `TRIAL` now resolves the same as `CANCELLED` (`Plan.NONE`).
  **Important limitation**: this makes the NEXT provisioning call for that org correct — it does
  not, by itself, make expiry take effect the instant the clock runs out. Nothing currently
  re-provisions purely because time passed; that's what TASK 28.2 ("Add a scheduled job... that
  transitions the org at expiry") is for, and it was **not built this session**. Until 28.2
  exists, a trial that expires with no other subscription event in between (webhook, admin
  action, plan change) keeps its last-provisioned access indefinitely.
- Tests: `PlatformOrganizationControllerTest` (org creation always yields a TRIAL subscription
  + flags provisioned), `FeatureFlagServiceTest` (new file — expired trial denies plan-gated
  features and zeroes limits; trial-still-active is unaffected).

## TASK 28.2 — Trial lifecycle [DONE] (2026-08-19 session)

- **28.2.a (trial length/what it includes)**: already fully defined by TASK 28.1's own work --
  `app.subscription.trial-days` (default 14), TRIAL resolves to STARTER-level access via
  `effectivePlan()` until `trialEndsAt`. Nothing new needed.
- **28.2.b (scheduled job)** -- the real remaining gap, and the one this session's own prior
  record flagged as its "Important limitation": new `TrialExpiryScheduler`
  (`server/scheduler/TrialExpiryScheduler.java`), mirroring `DunningScheduler`'s exact shape
  (daily `@Scheduled`+`@SchedulerLock` sweep, each org processed in its own `REQUIRES_NEW`
  transaction). Sends a reminder notification 3 and 1 days before `trialEndsAt`
  (`ORG_TRIAL_EXPIRING_SOON`, new `NotificationType`); at/after expiry, transitions the
  subscription to `CANCELLED` (this codebase has no dedicated `EXPIRED` status --
  `effectivePlan()` already treats CANCELLED and an expired TRIAL identically, so this stays
  within the states that already exist) and calls `provisionFlagsFor()` so the org's flags
  actually flip to NONE the same day the trial ends, not whenever some unrelated event next
  happens to re-provision. Notifies (`ORG_TRIAL_EXPIRED`, new `NotificationType`) and audits
  (`SUBSCRIPTION_CANCELLED`, reused -- same action Dunning's own terminal transition uses,
  `reason`/`metadata.trigger` distinguish the two causes).
- **28.2.c (expiry restricts, never deletes)**: satisfied structurally -- neither this job nor
  `provisionFlagsFor` touches any row outside `feature_flags`/`org_subscriptions`. A customer who
  converts after expiry re-provisions correctly via the existing subscription-change wiring
  (SYSTEM 20 TASK 20.3), no special-casing needed.
- **28.2.d (trial extension, audited)**: found **already fully built** in a session after this
  doc's original writing -- `PlatformSubscriptionController.extendTrial()` (`POST
  /{orgId}/extend-trial`), rejects extending anything not currently TRIAL, audits
  `ORG_TRIAL_EXTENDED`. No change needed.
- **28.2.e (web trial-status banner)**: explicitly out of scope for this backend-only phase, same
  boundary SYSTEM 18's rollup already used ("none of the 4 tasks' own ACCEPTANCE lines require"
  the web piece; TASK 28.2's own ACCEPTANCE line doesn't mention the banner either).

**Tests**: `TrialExpirySchedulerTest` (new, mirrors `DunningSchedulerTest`'s structure) -- reminder
at 3/1 days remaining, no-op on a non-reminder day, expiry transitions+audits+notifies, exact-now
boundary treated as expired, already-converted-since-sweep-list-built is a no-op.

## TASK 28.3 — Guided activation [DONE, backend] (2026-08-19 session)

New, from scratch -- nothing existed. 28.3.a's own suggested checklist (invite your team,
configure column schemas, import your first allocation file, run your first assignment) is
computed **live** against each step's real backing table on every read (`OnboardingServiceImpl`),
not a maintained onboarding-progress row -- same "live check, correct by construction" reasoning
`EntitlementServiceImpl`'s class javadoc already established for this codebase. `teamInvited`
deliberately requires MORE than one user: `PlatformOrganizationController.create()` always creates
the org's own initial admin, so "at least one user exists" would make that step permanently true
from the instant of org creation and never actually reflect whether the org invited anyone.

- **28.3.b (track + endpoint)**: `GET /api/v1/onboarding/checklist` (new `OnboardingController`,
  `Authz.LEADS`) returns `OnboardingChecklistResponse` for the caller's own org.
- **28.3.d (time-to-first-import)**: `timeToFirstImportMinutes` on the same response -- minutes
  between `Organization.createdAt` and the first COMPLETED/PARTIALLY_COMPLETED ALLOCATION
  upload's `createdAt`, null until that step is done. A plain numeric field, not an ISO-8601
  `Duration`, for simpler frontend consumption later. This is the metric itself, computed and
  exposed -- SYSTEM 33 (Analytics/BI, not in this phase's P0 scope) is where 28.3.d says the
  metric ultimately lives/aggregates, not a prerequisite for exposing the raw value per org now.
- **28.3.c (web checklist widget)**: out of scope, same backend-only boundary as 28.2.e.

New repository methods (`AssignmentRepository.existsByOrganizationIdAndIsDeletedFalse`,
`ColumnSchemaRepository.existsByOrganizationId`,
`FileUploadRepository.findFirstCompletedByOrganizationIdAndUploadType`) -- all live
exists/find-first queries, same style as the rest of this codebase's entitlement/limit checks.

**Tests**: `OnboardingServiceImplTest` (fresh org has nothing complete; each step true/false
independently; `teamInvited` specifically proven false with only the initial admin present;
time-to-first-import computed correctly; all-steps-true implies `allComplete`),
`OnboardingControllerTest` (missing org context rejects; delegates to the service for the
caller's own org).

## TASK 28.4 — Self-serve signup [SKIPPED, decision recorded] (2026-08-19 session)

Per 28.4.a's own instruction: "Only if the product intends self-serve. If onboarding is
sales-led, SKIP this task and record that decision." Confirmed sales-led/admin-provisioned, not
assumed: org creation exists at exactly one place in the entire codebase,
`PlatformOrganizationController.create()`, gated `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` --
no public signup endpoint, no unauthenticated org-creation path, anywhere. **Decision: skip.**
Revisit only if self-serve signup becomes an actual product goal -- a business decision, not a
technical one, same framing this doc's original session already gave it.

## SYSTEM 18 TASKs 18.2-18.4

Were "not done this session" in this doc's original writing -- since then, a later session
completed all of SYSTEM 18 (org suspend/reactivate/delete lifecycle, invite resend/revoke/list,
GDPR erasure). See `docs/SYSTEM-18-USER-ORG-MANAGEMENT.md`; SYSTEM 18's own rollup is `[x]`.

## Verification

Full `mvn -f server/pom.xml test` and SYSTEM 28's own specified command
(`-Dtest='*Onboarding*Test,*Organization*Test,*Trial*Test'`) both run at the end of this session
— see the session's final report for the actual pass/fail counts.

TASK 28.2/28.3 (2026-08-19 session): SYSTEM 28's own specified command
(`-Dtest='*Onboarding*Test,*Organization*Test,*Trial*Test'`) — 28/28 passed. Full
`mvn -f server/pom.xml clean test` run at the end of this session — see session's final report.
