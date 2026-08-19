-- SYSTEM 08 TASK 8.3: org-admin-controlled MFA-required policy, independent of the existing
-- platform-wide role-based enforcement (app.security.mfa.enforce). No existing policy field or
-- table covers this (confirmed by grep before adding one, per 8.3.a) -- and per 8.3.b, SYSTEM 37
-- (Configuration/Settings) itself confirms no unified org-settings table exists yet to add this to
-- instead ("Org-level configuration is spread across purpose-built tables rather than
-- centralized"), so this follows that same established pattern: a column on organizations
-- directly, matching deleted_at/purged_at/is_active already there, not a new settings table
-- invented ahead of SYSTEM 37's own future consolidation.
ALTER TABLE organizations ADD COLUMN mfa_required BOOLEAN NOT NULL DEFAULT FALSE;
