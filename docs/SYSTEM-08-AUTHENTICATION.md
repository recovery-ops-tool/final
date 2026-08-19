# SYSTEM 08 — Authentication: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 08 block. TASK 8.1 was done in an earlier
session (2026-08-18). This entry covers TASKs 8.2–8.4, done 2026-08-19 as the first of the
7 partial P0 systems in the user-directed phase-1 scope (16 P0s only, for the OCI deploy).

Backend fully done, `mvn clean test` green (774 tests, 0 failures — up from 753). SYSTEM 08's own
verification command (`-Dtest='*Auth*Test,*Token*Test,*Mfa*Test'`) matches 59 tests, all passing.

## TASK 8.1 — Fix spoofable client IP resolution [DONE, prior session]

See the original entry below this one's history — `ClientIpResolver`, three duplicate
`extractClientIp()` methods deleted, `ClientIpResolverTest` added. Not re-verified this session
(no code in this area changed).

## TASK 8.2 — Session visibility and revocation

**Already substantially built**, contradicting the tasklist's P1 listing — confirmed by reading the
code, not assumed: `GET /auth/sessions` (full detail: device, IP, geo, created/expires, anomaly
flags, `current` marker), `DELETE /auth/sessions/{id}` (single revoke, ownership-checked, already
audited with `AUTH_SESSION_REVOKED`), `POST /logout-all` (revoke everything including the caller's
own current session).

**Found the one real gap**: 8.2.c literally asks for `DELETE /api/v1/auth/sessions` — "revoke all
OTHERS," distinct from `/logout-all` (which also signs the caller's own session out). No such
endpoint existed. Added `RefreshTokenRotationService#revokeOtherSessions` (new
`RefreshTokenRepository.revokeAllByUserIdExceptDevice` query) + the controller endpoint. Falls back
to revoking everything if the caller sent no `X-Device-Id` (can't identify "current" to spare it).

**Verified the literal ACCEPTANCE** ("revoking one session from the other causes the revoked
session's next refresh to fail with 401") end-to-end, not just that each half works in isolation —
new test `revokeSession_thenRotateWithThatToken_throwsInvalidToken` calls the real
`revokeSession()` then the real `rotate()` with that same token and asserts `InvalidTokenException`
(→ 401 via `GlobalExceptionHandler`). Neither `revokeSession` nor this acceptance scenario had any
test coverage before this session despite the code existing.

Web frontend (8.2.e, "Active sessions" panel) deferred to the separate frontend phase, per standing
practice this pass (not required by the task's own numbered ACCEPTANCE line).

## TASK 8.3 — Org-level MFA enforcement policy

**8.3.a confirmed no existing policy field** (grepped first, as instructed) — MFA enforcement
before this was global-only: `app.security.mfa.enforce` + a role CSV
(`app.security.mfa.required-roles`), no per-organization toggle anywhere.

**8.3.b**: added `organizations.mfa_required` (migration V104) directly on the existing
`organizations` table, not a new settings table — SYSTEM 37 (Configuration/Settings, P2, out of
this phase's scope) itself confirms no unified org-settings table exists yet to add this to
instead ("Org-level configuration is spread across purpose-built tables"), so this follows that
same established pattern (matches `deleted_at`/`purged_at`/`is_active`, all already columns on
this table).

**8.3.c, written test-first per the task's own instruction** ("getting this wrong creates a
bypass"): `MfaServiceImpl#requiresMfaEnrollment` now checks the org's `mfaRequired` flag
UNCONDITIONALLY (independent of the platform-wide `app.security.mfa.enforce` switch — an org
opting itself into MFA is a tenant decision, not gated by an unrelated platform config flag),
falling through to the existing role-based check only when the org doesn't require it. Removed the
now-redundant `MfaService#isEnforced()` from the public interface entirely (folded into
`requiresMfaEnrollment`'s own logic; it was called from exactly one place and had no independent
test coverage). `AuthServiceImpl.login()`'s existing `MfaSetupRequiredException` branch — verified
already correct, not built new — throws BEFORE any token is issued, satisfying "grants NO usable
access token" exactly as written; new tests pin this end-to-end (enrollment-required blocks with
no token; already-enrolled proceeds to the normal TOTP-challenge step instead; not-required
proceeds normally).

**8.3.d**: org-admin toggle added to the existing self-service `PATCH /api/v1/organizations/me`
(already gated `Authz.ADMINS`, already scoped to the caller's own org) rather than a new endpoint —
`UpdateOrganizationRequest.mfaRequired` (nullable: omit to leave unchanged). Audited with a new
`AuditAction.ORG_MFA_POLICY_CHANGED` (migration V105) — distinct from `FEATURE_FLAG_CHANGED`,
which is specifically for `FeatureFlag` rows, a different mechanism. Web enrollment-required
interstitial and the toggle's own UI deferred to the frontend phase.

**MOBILE IMPACT note in the tasklist** ("confirm the mobile app handles an enrollment-required
response before enabling the org flag for any customer with mobile users") — not verified this
session; flagged for whoever first turns the flag on for a real org with mobile users.

## TASK 8.4 — Auth event audit coverage

**8.4.a/b**: read all four named files end-to-end specifically hunting for FAILURE branches with
no audit call, not just confirming the happy path (the task's own explicit instruction — "an audit
trail that only records successes is useless for investigation"). Found real, previously-zero-audit
gaps in every one of the four:

- **AuthServiceImpl.login()**: disabled-account rejection, invalid-recovery-code, and invalid-TOTP
  branches all threw with ZERO audit trail (only invalid-password, org-suspended, account-already-
  locked, and MFA-enrollment-required were ever audited). Fixed all three, plus added a NEW
  distinct audit row for the lockout TRANSITION itself (inside `handleFailedAttempt`, covering all
  three call sites that can trigger it) — separate from the existing "rejected, already locked"
  row, since "this attempt just now caused the lockout" is a different, equally investigation-
  relevant fact from "an already-locked account was hit again."
- **RefreshTokenRotationServiceImpl.rotate()**: a genuinely unknown/garbage/expired token (no user
  to attribute it to) had zero audit trail — only the theft-REPLAY sub-case was ever audited. Added
  `AUTH_UNAUTHORIZED` with `AuditActorType.ANONYMOUS`, the same category
  `JwtAuthenticationFilter`'s access-token path already uses for "no valid credentials at all"
  (SYSTEM 09 TASK 9.4.b). Also fixed disabled-account, org-suspended, and locked-account rejections
  at REFRESH time specifically — all three existed and correctly revoked tokens, but audited
  nothing.
- **PasswordResetServiceImpl.verifyResetOtp()**: wrong-OTP branch only ever wrote to the legacy
  free-text log, never to `unified_audit_events` — inconsistent with its sibling
  `resetPassword()`'s own wrong-OTP branch a few lines below, which already did both. Fixed to
  match.
- **MfaServiceImpl**: `enableMfa`/`disableMfa`'s own failure branches (rate-limited, invalid TOTP,
  replayed code) remain unaudited — a deliberate scope decision, not an oversight: these are
  self-service account-management flows (the account owner managing their own MFA), a materially
  different investigative signal from "someone trying to defeat MFA to log in as this user," which
  is what TASK 8.4's named examples (failed login, token theft, MFA failure AT LOGIN) are about and
  what this pass fixed. Flagged here for whoever picks up a future audit-completeness pass.

**Deliberately NOT added**: a structured `unified_audit_events` row for every SUCCESSFUL token
refresh. The task's acceptance names "a failed login, a successful login, and a detected token
reuse" — not successful refresh, which happens far more often than login and would meaningfully
add to a table already documented as write-heavy (V085's own indexing comment). The existing
legacy-log entry (`TOKEN_REFRESH`) stays as the record for that event; a reasoned choice, not a gap.

**Tests**: `AuthServiceImplAuditCoverageTest` (new, 4), `RefreshTokenRotationServiceImplTest` (+2),
`PasswordResetServiceImplTest` (+1).
