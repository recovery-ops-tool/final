# SYSTEM 18 — User & Organization Management: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 18 block, 2026-08-19 session — resumed
directly from the prior session's checkpoint (SYSTEM 09/12/13 done), continuing priority order
among the untouched P0 systems as the next fully-untouched one (18 → 19 → 39).

Backend fully done, 727 tests passing (703 + 24 new), `mvn clean test` green. Web/mobile UI pieces
(18.2.e, 18.3.c, and the MOBILE IMPACT note) are deliberately **not** built this session — see
"Deferred: web/mobile" at the end. None of the four tasks' own literal ACCEPTANCE lines require
them.

## TASK 18.1 — Remove client-supplied initial password [ALREADY DONE, verified only]

No code changes needed. `CreateOrganizationRequest` has no `adminPassword` field;
`PlatformOrganizationController.create()` already generates a random, thrown-away password hash
and sends a welcome OTP through the same `EmailService`/`PasswordResetToken` mechanism
`UserServiceImpl.createUser()` uses for ordinary users. This was fixed in a prior session (see the
existing `PlatformOrganizationControllerTest` regression suite, whose own comments reference
"SYSTEM-PLAN 18.1"). Confirmed by reading the DTO and controller directly rather than trusting the
tasklist's "CONCRETE BUG" note, which was stale.

## TASK 18.2 — Complete organization lifecycle

### 18.2.a — verify suspend/reactivate/trial-extend exist

- **Suspend/reactivate**: existed (`PATCH /{id}/active`, `PlatformOrganizationController.setActive`),
  already audited with `ORG_SUSPENDED`/`ORG_REACTIVATED`. What did NOT exist is covered in 18.2.b.
- **Trial extension**: `ORG_TRIAL_EXTENDED` was defined in the `AuditAction` taxonomy (V085) with
  **no endpoint that ever produced it** — a real gap, not a verification exercise. Added
  `POST /api/v1/platform/subscriptions/{orgId}/extend-trial` (`PlatformSubscriptionController`,
  where the rest of `OrgSubscription`-mutating admin actions already live) with an
  `ExtendTrialRequest{additionalDays, reason}` body. Compounds from the existing `trialEndsAt`
  (or "now" if it already lapsed) rather than resetting from now, so extending twice doesn't lose
  the first extension. Rejects if the subscription isn't currently `TRIAL`.

### 18.2.b — suspension must actually block access [the real gap]

**Found**: `setActive(false)` flipped `organizations.is_active` and wrote an audit row, but
**nothing in the authentication path ever read that flag**. `JwtAuthenticationFilter` built a
`UserPrincipal` straight from the `User` entity; `CustomUserDetailsService`, `AuthServiceImpl.login`,
and `RefreshTokenRotationServiceImpl.rotate` all checked `user.isEnabled()`/lockout but never the
user's organization. A suspended org's staff could keep working indefinitely — the endpoint
existed, but the resulting suspension was cosmetic.

Fixed at every checkpoint that issues or honours access:
- `UserPrincipal` gained a second constructor param, `organizationActive` (the single-arg
  constructor still defaults to `true` — used by 13 existing test/call sites that have no
  suspension concern of their own, e.g. JWT minting after a fresh login).
- `CustomUserDetailsService.loadUserByUsername` (the `@Cacheable("userDetails")` method
  `JwtAuthenticationFilter` calls on every authenticated request) now resolves the caller's
  `Organization` and sets that flag. Platform admins (`organizationId == null`) are always active.
- `JwtAuthenticationFilter` rejects with 403 `"Organization Suspended"` (new
  `OrganizationSuspendedException`, `GlobalExceptionHandler` entry added) when the flag is false —
  both the main request path and the SSE-ticket path. Audited via
  `AccessDenialAuditor.recordAccessDenied(request, actorUserId, "Organization suspended")` (added a
  reason-carrying overload; the existing 2-arg one now delegates to it).
- `AuthServiceImpl.login` and `RefreshTokenRotationServiceImpl.rotate` independently re-check
  organization active status against the DB (not the cache) before issuing a token — a suspended
  org's user can neither log in fresh nor refresh an existing session, and `rotate` also revokes
  all refresh tokens on rejection, same as the existing `!user.isEnabled()` branch.
- **Cache eviction, "next request" not "next 5 minutes"**: `setActive()` now calls
  `evictOrgUserCachesAfterCommit(orgId)` (new, mirrors `UserServiceImpl`'s existing per-user
  `evictUserCacheAfterCommit` pattern from SYSTEM 15 TASK 15.1, but loops every org member's email
  via a new `UserRepository.findEmailsByOrganizationId`). Without this, a suspended org's users
  would keep authenticating against their still-cached `UserPrincipal` for up to the "userDetails"
  cache's TTL.

**Tests**: `JwtAuthenticationFilterOrgSuspendedTest` (2), `CustomUserDetailsServiceOrgActiveTest`
(4 — platform admin/active/suspended/deleted), `AuthServiceImplOrgSuspendedTest` (2),
`PlatformOrganizationControllerTest#setActive_suspending_evictsCachedUserDetailsForEveryOrgMember`.

### 18.2.c — soft delete, retention window, then hard purge

**Found a second real gap along the way**: `delete()` previously called `orgRepo.delete(org)`
outright (guarded only by "zero users left"). Tried to build the literal "soft delete then hard
purge" the task describes, and hit a structural wall worth recording carefully:
**`unified_audit_events.organization_id` is a hard FK to `organizations(id)`, and
`trg_unified_audit_events_immutable` (V085, reusing V006's `fn_audit_log_immutable()`) blocks
UPDATE as well as DELETE on every audit row — including the implicit UPDATE Postgres issues
internally for an `ON DELETE SET NULL` FK action.** Verified this by reading the actual trigger
definition, not assumed. An org that ever generated a single audit event (every org, starting with
its own creation) cannot have its row physically deleted without either weakening the audit
trail's immutability guarantee or breaking referential integrity.

Resolution, consistent with the same position TASK 18.4.c takes for user erasure: **soft delete
(`deletedAt`/`deletionReason`, `isActive=false`) starts a retention window; `OrganizationPurgeJob`
(new, `@Scheduled` nightly + `@SchedulerLock`, mirrors `PiiKeyRotationJob`'s conventions) tombstones
the row after the window elapses instead of deleting it** — `name`/`code`/`contactEmail`/
`contactPhone`/`lookupHashPepper` scrubbed and overwritten with an id-qualified tombstone value
(guaranteed-unique against the `UNIQUE` constraints on `name`/`code`), `purgedAt` set, and the
org's own `OrgSubscription`/`FeatureFlag` rows deleted outright (nothing else references those by
their own PK, so no immutability conflict there). Deliberately does not cascade into business data
(allocations, borrowers, ptp records, ...) — `delete()` already refuses to soft-delete an org with
users still attached, so the common case reaching the job is an empty shell.

Also added `POST /{id}/restore` to reverse a soft delete within the window (before purge)  --
deliberately does NOT restore `isActive` to true (a suspended-then-deleted org stays suspended
after restoration; reactivate separately). Rejects if already purged (`purgedAt != null`).

New migration `V099__org_soft_delete_and_lifecycle_audit_actions.sql`: `organizations.deleted_at`/
`deletion_reason`/`purged_at`, plus extends `unified_audit_events`'s action CHECK constraint for
`ORG_DELETED`, `ORG_PURGED`, `USER_DATA_ERASED` (same convention V098 used).

**Tests**: `PlatformOrganizationControllerTest` (+5: delete soft-deletes / still-has-users throws /
restore clears deletedAt without re-activating / restore-after-purge throws / setActive evicts
every member's cache), `OrganizationPurgeJobTest` (2: tombstones + deletes admin rows when no users
remain / skips without mutating when users unexpectedly remain).

### 18.2.d — audit each transition

All five transitions now write a `unified_audit_events` row: `ORG_SUSPENDED`, `ORG_REACTIVATED`
(also reused for restore), `ORG_TRIAL_EXTENDED`, `ORG_DELETED`, `ORG_PURGED`.

## TASK 18.3 — User invite lifecycle completeness

**18.3.a**: this system doesn't have a separate "invite token" concept — the "invite" *is* the
welcome-OTP `PasswordResetToken` every new user already gets, and a user counts as still-pending
exactly when `lastLoginAt IS NULL` (they cannot have logged in without first redeeming that OTP to
set a real password — `passwordChangedAt` is NOT a usable signal for this, it defaults to the
creation instant via `@Builder.Default` and is therefore never actually null). Added, all gated the
same as the rest of user management (`Authz.LEADS`):
- `GET /api/v1/users/pending-invites` — `UserService.listPendingInvites`, new
  `UserRepository.findPendingInvitesByOrganizationId`.
- `POST /api/v1/users/{id}/invite/resend` — re-sends the welcome OTP (invalidating the previous
  one, same as the existing flow). Rejects once the user has actually logged in.
- `DELETE /api/v1/users/{id}/invite` — disables the account and invalidates any outstanding OTP.
  Same "still pending" guard.

**18.3.b**: already true, verified by reading `PasswordResetToken`/`PasswordResetTokenRepository`/
`PasswordResetServiceImpl` directly — `otpHash` (bcrypt, never plaintext), `used` flag set on
redemption, `expiresAt` enforced by `findValidByUserId`'s query. No code changes needed.

**18.3.c**: web — deferred, see below.

**Tests**: `UserServiceImplTest` (+5: resend on pending / resend-after-login throws / revoke on
pending / revoke-after-login throws / listPendingInvites delegates to the repository query).

## TASK 18.4 — GDPR user deletion

**18.4.a**: `deleteUser()`'s existing scrub (email/firstName/lastName → tombstone values) did not
touch `mfaSecret`/`mfaEnabled` — a "deleted" account's live TOTP seed was left intact. Fixed.

**18.4.b**: built a genuinely new erasure path, `UserService.eraseUserData` /
`POST /api/v1/users/{id}/erase` (gated tighter than ordinary user management —
`Authz.ADMINS`, not `Authz.LEADS`: an irreversible cross-table PII erasure is admin-only, unlike an
ordinary deactivate/delete a MANAGER/TL can already do). Found every denormalized copy of a
user's identity outside the `users` table by cross-referencing `PiiKeyRotationJob.ENCRYPTED_COLUMNS`
(the authoritative list of every `@Convert(EncryptedStringConverter.class)` column, built for
SYSTEM 07 TASK 7.2) against every FK into `users(id)`:
- `ptp_history.changed_by_name` (FK `changed_by`)
- `ptp_records.agent_name` (FK `agent_id`)
- `lucien_chat_sessions.agent_first_name` (FK `agent_id`)
- `user_creation_requests.requested_email`/`requested_first_name`/`requested_last_name` (FK
  `created_user_id`) — `requested_email` is plaintext, matching `users.email` (see 18.4.c doc
  below for why), scrubbed directly rather than through the encryption converter.

All four bulk-scrubbed via new JPQL `@Modifying` repository methods (`PtpHistoryRepository
#scrubChangedByName`, `PtpRepository#scrubAgentName`, `ChatSessionRepository#scrubAgentFirstName`,
`UserCreationRequestRepository#scrubByCreatedUserId`) — JPQL bulk UPDATE still routes the
tombstone value through the entity's `@Convert` attribute converter, so the encrypted columns land
correctly re-encrypted, not written as plaintext by accident.

**18.4.c**: documented in new `docs/PRIVACY.md` — `unified_audit_events.actor_user_id` is a bare
FK, never a denormalized copy, so once the `users` row is scrubbed the audit trail's identity
resolution is already a tombstone; nothing further needed there. `user_action_audit_logs.details`
(the legacy free-text table, already flagged in SYSTEM 01's memory as lacking RLS/an org column)
can embed an email in its message text and was **deliberately left unscrubbed** — regex-redacting
historical free text risks corrupting audit fidelity for a table SYSTEM 39 TASK 39.2 will address
as part of the full data-subject-request process, not this task.

**Tests**: `UserServiceImplTest` (+2: erasure scrubs the users row and every denormalized copy /
targeting self throws before touching anything).

## Deferred: web/mobile

`docs/PRODUCTION-TASKLIST.txt`'s SYSTEM 18 block lists `WEB FRONTEND: org lifecycle controls
(18.2e), pending invites UI (18.3c)` and a `MOBILE IMPACT` note about verifying the mobile app's
error message on a suspension-triggered 403. None of the four tasks' own numbered ACCEPTANCE
criteria require these — they're implementation notes below the acceptance line, not part of it.
Per the user's own stated plan (finish the full backend audit first, frontend work is a separate
later phase) and per standing guidance not to touch frontend logic without asking first, these are
left for that phase rather than built now. Backend endpoints exist and are tested; wiring a UI to
them is the remaining work.
