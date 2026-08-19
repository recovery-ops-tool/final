# SYSTEM 20 — Entitlement System: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 20 block, 2026-08-18 session.
Continuation of the same session that completed SYSTEM 35, SYSTEM 18 TASK 18.1, SYSTEM 28
TASK 28.1, SYSTEM 26 TASK 26.1, SYSTEM 15 TASKs 15.1–15.2, SYSTEM 08 TASK 8.1, and SYSTEM 10
TASKs 10.1–10.2.

## Prerequisite check

SYSTEM 19 done, per the tasklist's own state — specifically re-verified the one dependency TASK
20.1.d calls out by name (SYSTEM 19 TASK 19.2's dunning downgrade): `DunningScheduler` does have a
real terminal transition (PAST_DUE → CANCELLED after a configurable grace period, default 7 days),
it is reversible (payment recovery re-provisions via a webhook), and it is audited
(`SUBSCRIPTION_CANCELLED` with `reason`). Confirmed real, not assumed.

## Drift found before starting (stopped and reported, per this session's standing rule)

The tasklist's CURRENT STATE / VERIFIED PROBLEM section, though marked "RE-VERIFIED post-merge on
2026-08-17," no longer matched the code exactly:

1. **Still accurate**: `EntitlementService.hasFeature()` had zero callers, confirmed by grep.
2. **Stale**: the tasklist described `RequiresFeatureAspect` as still consulting
   `PlanFeatureMatrix` directly. By the time this session reached SYSTEM 20, that aspect had
   already been rewired (in an untracked prior change, evidenced by its own javadoc: "previously a
   decorative marker... wiring this") to call `FeatureFlagService.isEnabled()` — the *same*
   underlying service `EntitlementServiceImpl.hasFeature()` calls. `PlanFeatureMatrix` was only
   still consulted for provisioning defaults and an error-message label, not as a second,
   disagreeing gating decision. The real remaining disagreement was narrower than described: the
   aspect defaulted a missing flag row to ALLOW (fail-open), `EntitlementServiceImpl` defaulted the
   same lookup to DENY (fail-closed) — confirmed as a live risk, not theoretical, by tracing the
   window between a new org's creation and its first `provisionFlagsFor()` call, where no flag row
   yet exists.
3. **Real drift, not a bug**: `DunningScheduler`'s own class javadoc documents a deliberate,
   already-shipped policy — a PAST_DUE org keeps full plan access for a 7-day grace period; only an
   actual CANCELLED transition (after the grace period exhausts) revokes plan-derived features.
   This directly contradicted TASK 20.1's original acceptance criterion ("a PAST_DUE org... denied
   LUCIEN_AI"). Surfaced to you; you chose to keep the grace period as the correct, intentional
   policy and have the tasklist corrected to match it, rather than have this task override shipped
   product behavior. `docs/PRODUCTION-TASKLIST.txt`'s SYSTEM 20 block was corrected in place
   (CURRENT STATE and TASK 20.1.d/ACCEPTANCE) to describe this accurately for future sessions.

## TASK 20.1 — Converge on a single entitlement authority [DONE]

- `RequiresFeatureAspect` now injects and calls `EntitlementService.hasFeature(organizationId,
  flagKey)` exclusively — no longer reaches around it to `FeatureFlagService` directly. This gives
  `EntitlementService.hasFeature()` its first real caller (closing the "zero callers" finding
  through the mechanism the tasklist actually specified in 20.1.b, not just by deleting the dead
  interface method) and removes the fail-open/fail-closed disagreement: there is now exactly one
  missing-row default (`EntitlementServiceImpl`'s `false`), matching 20.1.e ("fail CLOSED — never
  default to allow").
- Per-org overrides/grants verified to exist and work, just not via the audit-enum names the
  tasklist's CURRENT STATE section assumed (`ENTITLEMENT_GRANTED`/`ENTITLEMENT_REVOKED` — confirmed
  by grep to have zero call sites anywhere, dead taxonomy). The real, working mechanism is
  `OrgSubscription.compedPlan`/`compedUntil` (resolved by `activeComp()`, audited as
  `FEATURE_FLAG_CHANGED` when applied via `FeatureFlagService.set()`), plus per-flag
  `FeatureFlag.FlagSource.MANUAL` overrides that `provisionFlagsFor()` deliberately skips
  re-provisioning over. Both are now test-covered (see below).
- Subscription-status resolution (`FeatureFlagService.effectivePlan()`) verified for every branch:
  TRIAL (in-window → STARTER, expired → NONE, from SYSTEM 28), ACTIVE (→ plan), CANCELLED/INACTIVE
  (→ NONE), and PAST_DUE (→ `null`, meaning `provisionFlagsFor` is a deliberate no-op — the grace
  period). A live comp grant (`activeComp()`) overrides all of the above, including during PAST_DUE
  — confirmed and tested, since a platform-admin comp should not evaporate just because billing is
  also stuck.

**Tests added** (none of this had direct unit coverage before, only indirectly via SYSTEM 28's
TRIAL-expiry test):
- `RequiresFeatureAspectTest` (new) — entitled passes through; not-entitled throws
  `AccessDeniedException`; no-authentication and no-organization cases are no-ops; confirms the
  aspect has no missing-row default of its own left to disagree with anything.
- `EntitlementServiceImplTest` (new) — `hasFeature()`'s fail-closed default; `canCreateUser()` /
  `canCreateAllocations()` at/below/above their limits and the unlimited (`Optional.empty()`) case.
- `FeatureFlagServiceTest` (extended) — added ACTIVE, CANCELLED, PAST_DUE-is-a-no-op, and
  PAST_DUE-with-active-comp-overrides-the-no-op cases alongside the pre-existing TRIAL tests.

**ACCEPTANCE** (as corrected): `RequiresFeatureAspect` no longer references `PlanFeatureMatrix` for
its gating decision (only for the error-message label) — met. An org whose subscription has
actually transitioned to CANCELLED is denied LUCIEN_AI — met, covered by the CANCELLED test. A
merely-PAST_DUE org is NOT denied — confirmed as correct, shipped behavior, locked in by the
PAST_DUE no-op test specifically so a future change can't silently end the grace period without a
test failing.

## TASK 20.2 — Enforce CUSTOM_INTEGRATIONS or remove it [DECISION RECORDED, NOT CHANGED]

Re-confirmed exactly as the tasklist states: `grep -rn "CUSTOM_INTEGRATIONS" server/src/main/java`
returns hits only in `PlanFeatureMatrix.java` (the flag key, its ENTERPRISE-plan membership, and
its error-message label) — no controller anywhere applies `@RequiresFeature(CUSTOM_INTEGRATIONS)`.
No drift from the tasklist's finding here.

**What's different from the tasklist's framing**: this isn't simple dead code with two easy options
(wire it to real endpoints, or delete it as harmless). Checked both directions:
- No backend feature exists for it to gate — there is no integrations controller, no outbound
  webhook configuration controller, and no developer API-key controller anywhere in
  `server/src/main/java` (SYSTEM 36 / SYSTEM 31 / SYSTEM 24 are simply not built yet). Wiring
  `@RequiresFeature(CUSTOM_INTEGRATIONS)` onto real endpoints per 20.2.c is not possible today.
- It IS actively marketed: `web/src/pages/SubscriptionPage.tsx` lists it by name ("Custom
  Integrations") as an Enterprise-tier perk, with supporting frontend plumbing in
  `FeatureGate.tsx` / `FeatureFlagsContext.tsx`. Deleting it per 20.2.d would mean un-selling a
  currently-marketed feature to Enterprise customers, not just removing dead code.

**Decision**: leave `PlanFeatureMatrix`, the pricing page, and the frontend gating untouched.
Removing a sold feature from marketing is a revenue/business decision outside this session's scope
to make unilaterally, and building real enforcement isn't possible until SYSTEM 36 exists. This
finding is recorded here explicitly so it isn't silently lost: **`CUSTOM_INTEGRATIONS` is sold on
the Enterprise plan today and enforces nothing — flagged for product/business decision** (build
SYSTEM 36 and gate it for real, or stop selling it). No code changed for this task.

## TASK 20.3 — Entitlement cache invalidation on subscription change [DONE — already substantially built, now tested]

Found already implemented, more thoroughly than the tasklist's "verify whether it caches, add if
not" framing assumed:
- `FeatureFlagService.isEnabled()` reads through an org-scoped Redis cache
  (`recoverpro:flag:{orgId}:{flagKey}`, 5-minute TTL) — org id is in the key, per SYSTEM 15's rule.
- `set()`/`setLimit()` evict that key synchronously, in the same call that persists the new value —
  no async gap where a stale cached value could still be served after a write returns.
- `provisionFlagsFor()` (which internally calls `set()`/`setLimit()` for every plan-gated flag/limit)
  is already invoked from every subscription-change path that exists: both payment providers'
  webhook handlers (`StripeWebhookService`, `RazorpayWebhookService`), platform-admin subscription
  actions (`PlatformSubscriptionController`, `PlatformOrganizationController`), self-service
  subscription changes (`SubscriptionController`), and the dunning terminal transition
  (`DunningScheduler`).

**Test added**: `FeatureFlagServiceTest.set_evictsCache_soNextEntitlementCheckReflectsChangeImmediately`
— simulates a stale cached "enabled" read, calls `set()` to flip it, asserts the cache key is
deleted, then simulates the resulting cache-miss and proves the next `isEnabled()` call re-resolves
the new value from the DB rather than serving anything stale. This directly exercises 20.3.d's
acceptance wording ("assert the next entitlement check reflects it immediately") at the unit level.

**ACCEPTANCE**: a subscription cancellation revokes feature access on the very next request — met
and now regression-tested (`provisionFlagsFor_cancelled_revokesPlanFeatures` plus the cache-eviction
test above, together covering both the DB write and the cache-invalidation halves of that claim).
"On every instance" (multi-pod) specifically relies on Redis being the shared L2 cache rather than
an instance-local one, which it already is — not re-verified against a live multi-instance
deployment this session (no such environment exists yet, see `docs/INFRA-CURRENT.md`).

## TASK 20.4 — Limit enforcement completeness [DONE, 4 of 5 named resources] (2026-08-19 session)

Task text names five resources to meter/cap: file uploads/month, report generations, Lucien AI
interactions, API requests, storage.

**Implemented — four new plan-derived limit keys added to `PlanFeatureMatrix.ALL_LIMIT_KEYS`**
(`FILE_UPLOADS_PER_MONTH`, `STORAGE_BYTES`, `REPORT_GENERATIONS_PER_MONTH`,
`LUCIEN_MONTHLY_TOKEN_LIMIT`), each enforced at the entry point before the expensive work per
20.4.c, each returning a `BusinessException`/`RateLimitExceededException` naming the specific limit
and the upgrade path per 20.4.d:

- **File uploads/month + storage** — `EntitlementService.canUploadFile()` /
  `canUseStorage(orgId, additionalBytes)`, both backed by live queries against `FileUpload`
  (`FileUploadRepository`'s two new count/sum-since queries — no maintained counter, same reasoning
  as `EntitlementServiceImpl`'s class javadoc). Checked in `FileUploadServiceImpl.initiateUpload()`
  before `fileValidationService.validateFile()` — the cheapest possible rejection point, ahead of
  hashing, storage, and async processing. Storage's sum excludes soft-deleted rows because
  `softDeleteFileUpload()` genuinely reclaims the underlying object via `FileStorageService.delete()`.
- **Report generations/month** — `EntitlementService.canGenerateReport()`, backed by a live count
  against `ReportJob` (new `ReportJobRepository.countByOrganizationIdAndCreatedAtAfter`). Checked
  first thing in `ReportingServiceImpl.enqueueReport()`, ahead of the existing
  already-queued/already-generating check and any DB write.
- **Lucien AI interactions** — a real per-org monthly cap already existed
  (`LucienTokenBudgetServiceImpl`, checked in `LucienServiceImpl.chat()` and `.ambientTurn()`
  before the model call, which also covers `/help` and `/ambient-turn` since they share that
  method) but was **not actually "capped per plan"**: `monthlyLimit` was a single flat `@Value`
  number applied identically to every org regardless of plan. Rewired to resolve
  `PlanFeatureMatrix.LUCIEN_MONTHLY_TOKEN_LIMIT` per-org through `EntitlementService.getLimit()`,
  same as the other three. Kept the existing Redis counter mechanism (a monthly token count is a
  natural INCR, not a cheap COUNT query like the others) and added a genuinely new, separate
  `lucien.token-budget.enabled` ops kill-switch, decoupled from the numeric cap it previously also
  served as (0 used to mean "disabled"; now 0 in `PlanFeatureMatrix` for STARTER/NONE just reflects
  those plans not having `LUCIEN_AI` at all, so the check is unreachable for them regardless).
  GROWTH's value (1,000,000) isn't a new guess -- it's the flat default this class already shipped
  with, now scoped to the one plan it used to apply to universally. ENTERPRISE stays unlimited,
  matching the same already-established convention `MAX_USERS`/`MAX_ACTIVE_LOANS` use (an empty
  `LIMITS` entry for Enterprise = unlimited for every key, a business decision from a prior session,
  not one made here).
- Provisioning, caching, and cache-invalidation for all four new keys come for free: they're just
  entries in `ALL_LIMIT_KEYS`, which `FeatureFlagService.provisionFlagsFor()` already iterates
  generically -- no new plumbing needed on top of SYSTEM 20 TASK 20.3's existing subscription-change
  wiring.

**Not implemented — "API requests"**: confirmed (same grep TASK 20.2 already ran for
`CUSTOM_INTEGRATIONS`) there is no developer/external API surface in this codebase distinct from
the web/mobile app's own authenticated calls -- no API-key controller, no webhook-config
controller, nothing SYSTEM 24/31/36 would eventually provide. "Capping API requests" for the
product's *own* frontend traffic would be general request throttling/API-gateway scope, not a
per-plan entitlement in the same sense as the other four (each of which is a distinct,
separately-metered cost driver). Recorded here rather than building a limit key with nothing real
to gate, mirroring TASK 20.2's own precedent for CUSTOM_INTEGRATIONS: flagged for whenever SYSTEM
36 (or a general API-key/rate-limit system) actually exists, not decided unilaterally now.

**Tests added**: `EntitlementServiceImplTest` (+8: unlimited/at-limit/below-limit for each of the
three new boolean methods), `FileUploadServiceImplTest` (new file -- both new checks, asserting no
validation/storage/DB work happens on rejection), `ReportingServiceImplEnqueueReportTest` (+1:
rejection before any write), `LucienTokenBudgetServiceImplTest` (new file -- this class had no
prior test at all: unlimited-plan skip, at-cap rejection with the new message, the enabled
kill-switch, and that `recordUsage` still tracks usage even for an unlimited-plan org).

**ACCEPTANCE** (as narrowed to four of five resources, API requests recorded as not-yet-applicable
above): each metered resource returns a specific limit-exceeded error naming the limit and upgrade
path — met for all four. Lucien AI usage is capped per plan — met (was previously capped
identically for every plan, now genuinely plan-derived).

Per the tasklist's own rollup rule, SYSTEM 20's rollup checkbox is now `[x]` — all 4 tasks
addressed (20.1 done, 20.2 a recorded decision, 20.3 done, 20.4 done for 4/5 named resources with
the 5th explicitly recorded as not-yet-applicable, not silently skipped).

## Verification

`mvn -f server/pom.xml clean test-compile` — clean.

SYSTEM 20's own specified command, `mvn -f server/pom.xml test -Dtest='*Entitlement*Test,*Feature*Test,*Plan*Test'`
— 35/35 passed (2026-08-18 session, TASKs 20.1-20.3). TASK 20.4's targeted run (`EntitlementServiceImplTest,
LucienTokenBudgetServiceImplTest,FileUploadServiceImplTest,ReportingServiceImplEnqueueReportTest,
ReportingServiceImplBankReconciliationTest,RequiresFeatureAspectTest,FeatureFlagServiceTest,LucienServiceImplTest`)
— 51/51 passed.

Full `mvn -f server/pom.xml clean test` — see session's final report for the run following this
record's creation.
