-- =============================================================================
-- V100__platform_invoices_provider_agnostic.sql
-- =============================================================================
-- SYSTEM 19 TASK 19.1: platform_invoices (V054) was built Stripe-shaped
-- (stripe_invoice_id/stripe_customer_id, NOT NULL) -- RazorpayWebhookService's own
-- class javadoc has flagged since V092 that it therefore has no way to mirror a
-- Razorpay invoice at all. V087 already anticipated this ("a future Razorpay
-- payment id") when it added provider_payment_ref as a generic column instead of
-- a Stripe-named one -- this migration finishes that generalization for the rest
-- of the table.
--
-- Existing rows all came from Stripe (Razorpay mirroring didn't exist before this
-- migration), so the backfill is unconditional.
-- =============================================================================

ALTER TABLE platform_invoices ADD COLUMN provider VARCHAR(20);
UPDATE platform_invoices SET provider = 'STRIPE' WHERE provider IS NULL;
ALTER TABLE platform_invoices ALTER COLUMN provider SET NOT NULL;

ALTER TABLE platform_invoices RENAME COLUMN stripe_invoice_id TO provider_invoice_id;
ALTER TABLE platform_invoices RENAME COLUMN stripe_customer_id TO provider_customer_id;

COMMENT ON COLUMN platform_invoices.provider_invoice_id IS
    'Stripe invoice id or Razorpay payment id (Razorpay Subscriptions has no separate '
    'invoice object -- the payment IS the billing event; see RazorpayWebhookService).';
