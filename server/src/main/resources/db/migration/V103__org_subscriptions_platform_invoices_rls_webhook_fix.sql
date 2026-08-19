-- =============================================================================
-- V103__org_subscriptions_platform_invoices_rls_webhook_fix.sql
-- =============================================================================
-- SYSTEM 19: found while writing a REAL integration test against the actual migrated schema for
-- TASK 19.5 (platform_invoices immutability) -- a plain INSERT into platform_invoices failed with
-- "new row violates row-level security policy", which is not supposed to be possible: V054's own
-- header comment explicitly documents why this table must NEVER have RLS ("the writer is the
-- Stripe webhook, which arrives with no JWT and therefore no tenant context, so current_org_id()
-- is null. An isolation policy would apply as the WITH CHECK expression on INSERT ... and block
-- the very writes this table exists to record").
--
-- V058 added RLS to platform_invoices anyway (a broader "close every remaining gap" sweep), with
-- the STRICT form (no current_org_id() IS NULL escape hatch) -- its own comment explains that
-- choice was about whether a ROW's organization_id being NULL is a deliberate platform-wide row
-- vs. a data bug, a genuinely different question from "does this SESSION have any tenant context
-- at all." It does not appear V054's specific warning was checked against before this table was
-- included in that sweep.
--
-- The practical consequence, confirmed by tracing the actual webhook code path (not assumed):
-- RlsContextFilter only sets RlsOrgIdHolder from an authenticated SecurityContext's UserPrincipal
-- (see RlsContextFilter.java) -- a webhook request carries no JWT, so SecurityContextHolder has no
-- Authentication at all for that request, and current_org_id() is NULL for the entire connection
-- for the whole duration of webhook processing, including the very FIRST read
-- (StripeWebhookService.requireByCustomerId / RazorpayWebhookService's
-- findByRazorpaySubscriptionId). The STRICT policy's `org_id = current_org_id()` therefore hides
-- the org's own row from the read that is supposed to find it -- there is no point later in
-- request handling where the application layer could set the right org id first, because it
-- cannot know which org until it has already been blocked from reading the row that would tell it.
--
-- org_subscriptions is not purely platform-admin/cross-tenant the way platform_invoices is --
-- SubscriptionController's self-service read path scopes it to the CALLER's own org for a real
-- authenticated user, and that path is unaffected by this change: RlsContextFilter always sets a
-- real, non-null org id for an authenticated request, so the OR current_org_id() IS NULL branch
-- below can only ever apply when there is truly no session/tenant context at all (exactly the
-- webhook case) -- restoring the SAME fail-open shape V010's original tenant-table policies
-- (allocations, etc.) already use, not inventing a new, weaker pattern.
--
-- user_creation_requests / lucien_chat_sessions / lucien_chat_messages (the other three tables
-- V058 gave the strict policy) are NOT touched here -- no code path writes to them without an
-- authenticated session's org context, so they don't have this specific problem. Flagged as worth
-- independently re-verifying, not assumed safe by pattern-matching alone.
-- =============================================================================

DROP POLICY rls_org_subscriptions_isolation ON org_subscriptions;
CREATE POLICY rls_org_subscriptions_isolation ON org_subscriptions
    USING (org_id = current_org_id() OR current_org_id() IS NULL);

DROP POLICY rls_platform_invoices_isolation ON platform_invoices;
CREATE POLICY rls_platform_invoices_isolation ON platform_invoices
    USING (org_id = current_org_id() OR current_org_id() IS NULL);
