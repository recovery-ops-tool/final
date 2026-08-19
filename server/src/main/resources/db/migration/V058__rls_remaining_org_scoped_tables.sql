-- V058__rls_remaining_org_scoped_tables.sql
-- Found while triaging why RLS coverage stopped at V057: these 7 tables carry
-- organization_id/org_id and are actively queried by real application code,
-- but never got RLS at all (not even fail-open) -- confirmed live via
-- pg_class.relrowsecurity as of schema version 057. App-layer scoping exists
-- for some of these already (e.g. SubscriptionController derives orgId from
-- the authenticated principal, never from client input), so this migration
-- is defense-in-depth for those and closes a real gap for the others.
--
-- roles and feature_flags get a NULL-aware policy on purpose: both have
-- dedicated repository methods (RoleRepository.findByOrganizationIdIsNull,
-- FeatureFlagRepository.findByOrganizationIdIsNull) proving a NULL
-- organization_id is a deliberate "platform-wide" row, not a missing value --
-- different from the current_org_id() session-variable-null fail-open bug
-- fixed in V040/V057 (that checked whether the SESSION context was unset;
-- this checks whether the ROW itself is intentionally platform-scoped).
-- The other 5 tables have no such evidence of intentional NULL rows, so they
-- get the strict policy: a NULL organization_id there is a data problem, not
-- a designed state, and should be invisible under any tenant context.

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_roles_isolation ON roles
    USING (organization_id = current_org_id() OR organization_id IS NULL);

ALTER TABLE feature_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE feature_flags FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_feature_flags_isolation ON feature_flags
    USING (organization_id = current_org_id() OR organization_id IS NULL);

ALTER TABLE org_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_org_subscriptions_isolation ON org_subscriptions
    USING (org_id = current_org_id());

ALTER TABLE platform_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_invoices FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_platform_invoices_isolation ON platform_invoices
    USING (org_id = current_org_id());

ALTER TABLE user_creation_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_creation_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_user_creation_requests_isolation ON user_creation_requests
    USING (organization_id = current_org_id());

-- SYSTEM 03 TASK 3.1 correction (added 2026-08-18, applied checksum repaired via `flyway repair`):
-- neither friday_chat_sessions (V001) nor its rename to lucien_chat_sessions (V003) ever had an
-- organization_id column, and no migration between V003 and this one added it -- a truly fresh
-- `flyway migrate` from V001 failed here with "column organization_id does not exist", caught by
-- running the full chain against a scratch database (see docs/RUNBOOK-DEPLOY.md). Every environment
-- that had already applied this migration successfully only worked because the column was present
-- through undocumented manual drift, not through any tracked migration. Adding it here, defensively
-- and idempotently, makes this migration self-sufficient instead of depending on that drift.
ALTER TABLE lucien_chat_sessions ADD COLUMN IF NOT EXISTS organization_id UUID;
ALTER TABLE lucien_chat_messages ADD COLUMN IF NOT EXISTS organization_id UUID;

ALTER TABLE lucien_chat_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE lucien_chat_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_lucien_chat_sessions_isolation ON lucien_chat_sessions
    USING (organization_id = current_org_id());

ALTER TABLE lucien_chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE lucien_chat_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_lucien_chat_messages_isolation ON lucien_chat_messages
    USING (organization_id = current_org_id());

-- Deliberately NOT RLS'd, with reasons:
--   users            -- findByEmail is the core login lookup, called before
--                        any org context exists; RLS here would break
--                        authentication itself.
--   receipt_sequences -- only ever accessed via a keyed INSERT...ON CONFLICT
--                        upsert with the exact organization_id passed by the
--                        calling code (ReceiptSequenceRepositoryImpl); no
--                        listing/broad-query surface exists to protect.
--
-- Also found during this triage but NOT touched here -- confirmed dead:
-- no Flyway migration creates them as real app tables AND no live Java code
-- queries them (audit_events, call_logs, notifications, rag_chunks,
-- rag_documents_orgscoped_orphan_backup are leftover/superseded artifacts;
-- friday_chat_messages/friday_chat_sessions predate a product rename to
-- "Lucien" and were superseded by lucien_chat_messages/lucien_chat_sessions
-- above). Left alone rather than dropped since that's a schema-hygiene
-- decision, not a security one.
