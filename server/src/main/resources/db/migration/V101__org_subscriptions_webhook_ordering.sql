-- =============================================================================
-- V101__org_subscriptions_webhook_ordering.sql
-- =============================================================================
-- SYSTEM 19 TASK 19.3.c: neither StripeWebhookService.handleSubscriptionUpserted nor
-- RazorpayWebhookService's subscription-state sync compared the incoming event against
-- anything -- every delivery unconditionally overwrote status/plan/period, so a stale
-- webhook redelivered (or delivered late, out of order) after a newer one would silently
-- regress the subscription back to older state. Tracks the timestamp of the last webhook
-- event actually applied so a stale one can be detected and skipped instead.
-- =============================================================================

ALTER TABLE org_subscriptions ADD COLUMN last_webhook_event_at TIMESTAMPTZ;
