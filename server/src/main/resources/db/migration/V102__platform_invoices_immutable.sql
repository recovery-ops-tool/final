-- =============================================================================
-- V102__platform_invoices_immutable.sql
-- =============================================================================
-- SYSTEM 19 TASK 19.5: once an invoice reaches a terminal, settled state (paid, void,
-- uncollectible), its financial facts must not change -- corrections happen via Refund/Credit
-- (both already exist), never by editing the original invoice.
--
-- platform_invoices is NOT append-only the way unified_audit_events/ptp_audit_logs are (the SAME
-- row legitimately transitions draft/open -> paid, or -> void, as StripeWebhookService.upsertInvoice
-- and RazorpayWebhookService.mirrorInvoice keep it in step with the provider), so this cannot reuse
-- fn_audit_log_immutable()'s blanket "any UPDATE fails" the way V084/V085 did -- that would break
-- the very upsert this table exists for. Instead: once OLD.status is already terminal, only the
-- FINANCIAL/identity columns are protected (and only when the incoming value actually differs --
-- a same-value webhook redelivery, Stripe/Razorpay's own retry semantics, must stay a safe no-op).
-- hosted_invoice_url/invoice_pdf_url/updated_at are deliberately NOT protected -- a provider
-- refreshing a document URL after settlement is not a correction of the financial record.
--
-- invoice_line_items (SYSTEM 19 TASK 19.1's GST line items) has no legitimate update path at all
-- (GstInvoiceLineItemServiceImpl only ever INSERTs) -- reuses fn_audit_log_immutable() directly,
-- same convention V084 already used for settlement_audit_logs/allocation_audit_logs.
-- =============================================================================

CREATE OR REPLACE FUNCTION fn_platform_invoice_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('paid', 'void', 'uncollectible') THEN
        IF NEW.status              IS DISTINCT FROM OLD.status
        OR NEW.amount_due          IS DISTINCT FROM OLD.amount_due
        OR NEW.amount_paid         IS DISTINCT FROM OLD.amount_paid
        OR NEW.currency            IS DISTINCT FROM OLD.currency
        OR NEW.paid_at             IS DISTINCT FROM OLD.paid_at
        OR NEW.provider_invoice_id IS DISTINCT FROM OLD.provider_invoice_id
        OR NEW.provider            IS DISTINCT FROM OLD.provider
        OR NEW.org_id              IS DISTINCT FROM OLD.org_id
        THEN
            RAISE EXCEPTION
                'platform_invoices is immutable once issued (id=%, status=%): use a Refund or Credit instead of editing the invoice',
                OLD.id, OLD.status;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_platform_invoices_immutable
    BEFORE UPDATE ON platform_invoices
    FOR EACH ROW EXECUTE FUNCTION fn_platform_invoice_immutable();

CREATE TRIGGER trg_invoice_line_items_immutable
    BEFORE UPDATE OR DELETE ON invoice_line_items
    FOR EACH ROW EXECUTE FUNCTION fn_audit_log_immutable();
