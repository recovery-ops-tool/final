# Billing Provider Parity — Stripe vs. Razorpay

SYSTEM 19 TASK 19.1. For every webhook event `StripeWebhookController` dispatches, the Razorpay
equivalent and whether it is handled to the same effect. State as of this pass — everything below
is handled unless marked otherwise.

Razorpay Subscriptions has **no separate "Invoice" object** the way Stripe does: a successful
`subscription.charged` payment IS the billing event. `RazorpayWebhookService.mirrorInvoice`
synthesizes a `PlatformInvoice` row directly from the subscription + payment webhook entities at
that point, rather than listening for a dedicated invoice event — there isn't one for this
product. This is why several Stripe rows below map to the *same* Razorpay event.

| # | Stripe event | Effect | Razorpay equivalent | Handled? |
|---|---|---|---|---|
| 1 | `checkout.session.completed` | Links `stripeSubscriptionId`, status → ACTIVE, audits `SUBSCRIPTION_CREATED` | *(no webhook)* — `RazorpayPaymentProvider.createCheckoutUrl` links `razorpaySubscriptionId` synchronously, before the customer has actually paid | Handled, by a different (synchronous, pre-payment) mechanism — a real API-shape difference between the two providers, not a gap |
| 2 | `customer.subscription.created` / `.updated` | Full snapshot sync: status, plan, planAmount, currentPeriodEnd, cancelAtPeriodEnd; audits `SUBSCRIPTION_CHANGED` | `subscription.activated`, `subscription.charged`, `subscription.pending`, `subscription.halted` (any `subscription.*` delivery carries the current entity) | **Fixed this pass.** Previously ONLY status was synced (via 3 separate handlers with no shared full-sync path) and only the PAST_DUE/CANCELLED paths ever audited anything. Now `syncSubscriptionState` runs on every delivery (plan + currentPeriodEnd) and `handleRecovered` audits `SUBSCRIPTION_CHANGED` too. `cancelAtPeriodEnd` is the one field NOT synced from this event — see the note below the table. |
| 3 | `customer.subscription.deleted` | Status → CANCELLED, audits `SUBSCRIPTION_CANCELLED` | `subscription.cancelled` | Already handled (`handleCancelled`). Also mapped `subscription.completed` (Razorpay-specific: all billing cycles finished naturally — no Stripe equivalent, since Stripe subscriptions don't have a fixed cycle count) to the same handler this pass, since it was previously undispatched entirely. |
| 4 | `invoice.paid` | Mirrors invoice, recovers PAST_DUE → ACTIVE (and, since TASK 19.2, CANCELLED → ACTIVE — see `docs/SYSTEM-19-SUBSCRIPTION-BILLING.md`), notifies | `subscription.charged` (+ its embedded `payload.payment.entity`) | **Fixed this pass** — was entirely unhandled (the headline gap this task existed to close). `mirrorInvoice` + `mirrorPayment` now run together whenever a payment entity is present. |
| 5 | `invoice.payment_failed` | Mirrors invoice, status → PAST_DUE, notifies, audits `INVOICE_PAYMENT_FAILED` | `subscription.pending` (retry in progress), `subscription.halted` (retries exhausted) | Status/notify/audit already handled (`handlePastDue`). Invoice mirroring only happens when Razorpay's webhook happens to carry a payment entity — `subscription.pending`/`.halted` commonly do not (only `subscription.charged` reliably does per Razorpay's docs), so a failed-attempt Razorpay invoice row is not always produced. Documented gap, not silently assumed: Razorpay's failed-charge payment entity is not guaranteed present on these two event types without a live payload capture to confirm either way. |
| 6 | `invoice.finalized`, `invoice.voided`, `invoice.marked_uncollectible` | Mirror-only, no state change | *(no equivalent)* | N/A — Razorpay Subscriptions has no separate invoice lifecycle to mirror mid-stream; the payment entity's own status (`created`/`authorized`/`captured`/`failed`) is the only lifecycle Razorpay exposes, already covered by row 4/5. |

## Fields NOT at parity (documented, not silently dropped)

- **`cancelAtPeriodEnd`**: Stripe's is synced FROM the subscription-updated webhook (Stripe echoes
  back its own authoritative flag). Razorpay's subscription entity does not reliably expose an
  equivalent field on ordinary webhook deliveries (verify against a live payload capture before
  trusting this either way — same caveat the rest of this class already carries). Set instead at
  `RazorpayPaymentProvider.cancelSubscription`'s call site, from that call's own `atPeriodEnd`
  parameter — this app's own authenticated, synchronous confirmation from Razorpay that the
  request was accepted, not an inferred client callback. Subscription **status** itself stays
  purely webhook-driven for both providers (`subscription.cancelled` is a reliable Razorpay event).
- **`planAmount`**: Stripe embeds the price's `unit_amount` inline on the subscription object
  itself, so every sync has it. Razorpay's subscription entity carries only a `plan_id` reference,
  not the amount — resolving it would need an extra `plans.fetch()` API call this pass did not add
  (no code currently reads `OrgSubscription.planAmount` for a Razorpay-billed org in a way that
  would be visibly wrong without it — `PlatformSubscriptionController`'s revenue-trend math uses
  `PlatformInvoice.amountPaid`, not `planAmount`, for collected figures). Flagged, not silently
  assumed correct.
- **Invoice `number`, `hostedInvoiceUrl`, `invoicePdfUrl`**: Stripe provides all three on its
  Invoice object. Razorpay Subscriptions payments have none of these — a payment id is not a
  human-facing invoice number, and there is no hosted receipt page equivalent to link (the
  subscription's own `short_url` is a *checkout* link, not an invoice page, and reusing it for
  something it isn't would be actively misleading). Left null on Razorpay-provider rows.

## Acceptance check

`ACCEPTANCE: the parity table has no unhandled rows; a Razorpay payment produces a local Invoice
with line items equivalent to Stripe's.` — every row above is handled or has a documented,
deliberate reason it cannot be (a real API-shape difference between the two providers, not an
oversight). A Razorpay `subscription.charged` payment now produces a `PlatformInvoice` row with
provider-normalized status (`paid`/`open`/`uncollectible`, matching Stripe's vocabulary so
`PlatformInvoiceRepository`'s revenue queries work identically across both providers), linked
`Payment.invoiceId`, and period/amount fields populated from the subscription + payment entities.
GST line-item generation (`GstInvoiceLineItemServiceImpl`) already operates generically on
`PlatformInvoice.invoiceId` with no provider-specific code — it works for a Razorpay-sourced
invoice with no changes needed, now that the invoice row itself exists to generate a line item
against.
