# Privacy & GDPR Erasure — Position Statements

This document records the deliberate design decisions behind SYSTEM 18 TASK 18.4 (GDPR user
erasure) and SYSTEM 18 TASK 18.2.c (organization deletion/purge). It is not a customer-facing
privacy policy — it is the internal record of *why* erasure works the way it does, so a future
reviewer (or auditor) can see the reasoning, not just the code.

A full PII data inventory and the broader data-subject-request process (access, correction,
portability, backups, JSONB columns outside the users table) are SYSTEM 39's job (TASK 39.1,
39.2), not this document's. This covers exactly what SYSTEM 18 TASK 18.4/18.2.c needed to decide.

## 1. User erasure (`UserService.eraseUserData`)

### What gets scrubbed

| Table | Column(s) | How |
|---|---|---|
| `users` | `email`, `first_name`, `last_name` | Overwritten with a per-user tombstone value (`erased-<id>@recoverpro.internal`, `[Erased]`) |
| `users` | `mfa_secret`, `mfa_enabled` | Cleared — a live TOTP seed is not "deleted" just because the account is disabled |
| `users` | `password_hash` | Replaced with a fresh random value (never usable, never logged) |
| `ptp_history` | `changed_by_name` | Bulk-scrubbed for every row where `changed_by` = the erased user |
| `ptp_records` | `agent_name` | Bulk-scrubbed for every row where `agent_id` = the erased user |
| `lucien_chat_sessions` | `agent_first_name` | Bulk-scrubbed for every row where `agent_id` = the erased user |
| `user_creation_requests` | `requested_email`, `requested_first_name`, `requested_last_name` | Scrubbed for the request row that produced this user (`created_user_id`) |

The three `_name` columns above are all `@Convert(EncryptedStringConverter.class)` — genuinely
encrypted at rest, not plaintext — but they are still **denormalized copies** of a name that also
lives (now scrubbed) on the `users` row, which is exactly the class of gap TASK 18.4.b asked to be
found: "duplicated elsewhere," not necessarily "duplicated in a JSONB column." They were found by
cross-referencing `PiiKeyRotationJob.ENCRYPTED_COLUMNS` (the authoritative list of every encrypted
column in the schema, built for SYSTEM 07 TASK 7.2) against every FK into `users(id)`.

`user_creation_requests.requested_email` is plaintext, matching `users.email` (see §3 below) — it
is overwritten directly rather than through the encryption converter.

### What is deliberately NOT scrubbed here

- **`unified_audit_events`**: `actor_user_id` is a bare foreign key to `users(id)`, never a
  denormalized name/email copy (confirmed by reading V085's schema directly, not assumed). Once
  the `users` row above is scrubbed, resolving that FK already returns the tombstone — there is
  nothing further to write. This *is* the tombstoning TASK 18.4.c asks for: the row survives (it
  must — audit rows are legitimate-interest data, kept intentionally), the identity behind it does
  not.
- **`user_action_audit_logs.details`**: a legacy free-text column (pre-dates the structured
  `unified_audit_events` table; see SYSTEM 01's memory note that this table also has no RLS and no
  organization column — a separate, already-flagged gap). Some call sites format an email/name
  directly into this text (e.g. `PlatformOrganizationController.updateAdmin`'s `"new email=" +
  newEmail`). Regex-scrubbing free text after the fact risks corrupting audit fidelity for a
  marginal privacy gain, and this table's replacement (`unified_audit_events`) does not have the
  problem at all. **Left as a documented, known residual exposure** rather than attempted —
  consistent with SYSTEM 39 TASK 39.2 owning the full backup/export/audit-trail nuance of erasure,
  not this task.
- **`refresh_tokens` / session data**: not PII-bearing (device info, IP, token hashes) and already
  gets revoked as part of account deactivation elsewhere; erasure does not need to touch it.

### Why the row survives instead of being deleted

Same reasoning as organization purge below: the `users` row is referenced by FK from many places
(`unified_audit_events.actor_user_id`, `ptp_records.agent_id`, etc.), several of which are
protected by `fn_audit_log_immutable()` triggers that block both UPDATE and DELETE. A hard
`DELETE FROM users` would either violate referential integrity or be blocked outright. Scrubbing
in place is the only approach that satisfies "erase the PII" and "never break the audit trail" at
the same time.

## 2. Organization purge (`OrganizationPurgeJob`)

`PlatformOrganizationController#delete` only starts a retention window (soft delete,
`deleted_at`). `OrganizationPurgeJob` runs nightly and, once the window elapses, **tombstones**
the `Organization` row rather than deleting it: `name`/`code`/`contact_email`/`contact_phone`/
`lookup_hash_pepper` are overwritten, the org's own `OrgSubscription` and `FeatureFlag` rows are
deleted outright (nothing else references those by their own PK), and `purged_at` is set.

This is a structural necessity, not a stylistic choice: `unified_audit_events.organization_id` is
a hard FK to `organizations(id)`, and `trg_unified_audit_events_immutable` blocks UPDATE as well
as DELETE on every audit row — including the implicit UPDATE Postgres would issue internally for
an `ON DELETE SET NULL` FK action. An org that ever generated a single audit event (every org,
starting with its own creation) cannot have its row physically deleted without weakening the audit
trail's immutability guarantee. Tombstoning in place was verified against the actual trigger
definition (`V006__audit_log_immutable_trigger.sql`), not assumed.

## 3. Why `users.email` (and `user_creation_requests.requested_email`) are plaintext

Both are deliberately unencrypted, not an oversight: `AuthServiceImpl.login` and
`CustomUserDetailsService.loadUserByUsername` need to query `users` directly by email
(`WHERE email = ?`), and an application-layer encryption converter would make that an equality
match against ciphertext, which only works if the encryption is deterministic — defeating the
point of using an authenticated (non-deterministic) cipher elsewhere in this codebase
(`EncryptedStringConverter`/AES-GCM, per SYSTEM 07). The codebase's existing alternative for
"encrypted value + still searchable" is a blind-index lookup hash (see `Borrower.emailLookupHash`
for the borrower-PII equivalent) — retrofitting that onto `users.email` is a real, larger piece of
work (touches login, uniqueness checks, and every `findByEmail` call site) that SYSTEM 39 TASK
39.1's full PII inventory should size and schedule, not something folded into this task.

## 4. Response deadline

Not yet defined — this is SYSTEM 39 TASK 39.2.d's job (it owns the full data-subject-request
process, including the timeline commitment). This document only covers the mechanism.
