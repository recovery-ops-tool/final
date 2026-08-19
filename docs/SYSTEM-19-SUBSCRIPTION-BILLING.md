# SYSTEM 19 — Subscription & Billing: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 19 block, 2026-08-19 session — resumed
directly from the SYSTEM 18 checkpoint, continuing priority order as the next fully-untouched P0.
User then redirected scope mid-session: finish the 16 P0 systems only (for a phase-1 OCI deploy),
defer the other 26 of the 42 to a later phase.

Backend fully done, 753 tests passing (727 + 26 new), `mvn clean test` green.

## TASK 19.1 — Complete Razorpay invoice mirroring [P0]

`docs/BILLING-PROVIDER-PARITY.md` has the full handler-by-handler table. Summary of what changed:

- **`platform_invoices` generalized to be provider-agnostic** (new migration V100): was hard
  Stripe-shaped (`stripe_invoice_id`/`stripe_customer_id`, `NOT NULL UNIQUE`) — V087 had already
  anticipated this ("a future Razorpay payment id") when it added `provider_payment_ref` as a
  generic column instead of a Stripe-named one; this finishes that generalization. Renamed to
  `provider_invoice_id`/`provider_customer_id`, added a `provider` enum column.
- **Razorpay invoice mirroring implemented** (`RazorpayWebhookService.mirrorInvoice`, the headline
  gap): Razorpay Subscriptions has no separate "Invoice" object the way Stripe does — a successful
  `subscription.charged` payment IS the billing event, so the invoice row is synthesized directly
  from the subscription + payment webhook entities. Status translated into Stripe's vocabulary
  (`paid`/`open`/`uncollectible`) so `PlatformInvoiceRepository`'s revenue queries
  (`WHERE status = 'paid'`) work identically across both providers, unlike `Payment.status` which
  deliberately stays raw per-provider.
- **Subscription-state sync gap closed**: `plan`/`currentPeriodEnd` were never synced for Razorpay
  anywhere before this (only `status`, via three separate handlers with no shared full-sync path).
  New `syncSubscriptionState` runs on every `subscription.*` delivery, mirroring Stripe's
  `handleSubscriptionUpserted` full-snapshot approach.
- **`cancelAtPeriodEnd` gap closed**: never persisted for Razorpay. Razorpay's subscription entity
  doesn't reliably expose an equivalent field on ordinary webhook deliveries (documented, not
  guessed at), so it's set synchronously at `RazorpayPaymentProvider.cancelSubscription`'s call
  site instead — a deliberate, narrow exception to "webhooks are the source of truth," explained
  in that method's javadoc. Subscription status itself stays purely webhook-driven for both
  providers.
- **Audit parity**: `handleRecovered` (Razorpay activation/renewal) previously wrote zero audit
  events — only the PAST_DUE/CANCELLED paths did. Now audits `SUBSCRIPTION_CHANGED` on every sync,
  matching Stripe.
- Also routed Razorpay's `subscription.completed` (all billing cycles finished naturally — a
  Razorpay-specific terminal state with no Stripe equivalent) to the same cancellation handling;
  it was previously undispatched entirely.

**Tests**: `RazorpayWebhookServiceTest` (+7: invoice mirroring paid/failed, payment↔invoice
linkage, plan/period sync, unrecognized-plan fallback, activation audit, `subscription.completed`).

## TASK 19.2 — Dunning terminal state and downgrade [P0]

**Already substantially built** (`DunningScheduler`, pre-existing): retry delegated to the
provider's own schedule, reminders at day 1/4, terminal action after the grace period (default 7
days) transitions to CANCELLED (→ `FeatureFlagService` maps this to `Plan.NONE`, i.e. unentitled),
notifies the org admin at each step and the terminal step, audits with a reason. TASK 19.2.b/d/e's
literal requirements were already met — verified, not built.

**Found and fixed the real gap (19.2.c, reversibility)**: `expireSubscription()` only ever flips
the LOCAL status to CANCELLED — it never calls the provider's own cancel API. So a subscription
this app believes is dead can still be legitimately billed and paid on the provider's side. Traced
`StripeWebhookService.handleInvoicePaid`'s recovery condition and found it only checked
`status == PAST_DUE` — a late Stripe `invoice.paid` for a CANCELLED-via-dunning org never restored
access; the org would stay locked out even after paying. Fixed: recovery condition broadened to
`PAST_DUE || CANCELLED`. Safe because Stripe cannot fire `invoice.paid` at all for a subscription
it has actually terminated — if the event arrives, Stripe itself still considers the subscription
live. (Razorpay's `handleRecovered` already had no status guard at all — it already recovered
correctly by what looks like accident of implementation, confirmed not a gap.)

**Tests**: `StripeWebhookServiceTest#handleInvoicePaid_recoversFromCancelled_notJustPastDue`.

## TASK 19.3 — Verify plan change and proration [P1]

All five lifecycle scenarios + the out-of-order case, against real behavior:

- **Upgrade / downgrade**: `PaymentProvider#changePlan`'s existing javadoc already documents the
  product decision — both apply IMMEDIATELY; downgrade just doesn't get a prorated credit (not
  "deferred to period end," a genuinely different policy some SaaS products use but this one
  doesn't). New `StripePaymentProviderTest` (didn't exist before this pass) pins the actual
  `SubscriptionUpdateParams` sent (`CREATE_PRORATIONS` vs `NONE`, price changed in the SAME call
  either way).
- **Cancel-at-period-end / immediate cancel**: same new test file, verifies
  `setCancelAtPeriodEnd(true)` vs `.cancel()` directly.
- **Reactivate after cancel**: covered by TASK 19.2's fix above.
- **Out-of-order webhook protection (19.3.c) — a real gap, not just a test to write**: neither
  webhook service compared an incoming event against anything before overwriting subscription
  state; every delivery unconditionally applied. Added `org_subscriptions.last_webhook_event_at`
  (new migration V101) and an ordering guard in both `StripeWebhookService.handleSubscriptionUpserted
  /handleSubscriptionDeleted` and `RazorpayWebhookService.handleSubscriptionEvent`: an incoming
  event older than the last one actually applied is skipped (logged, not applied), comparing
  Stripe's `Event.created` / Razorpay's envelope `created_at` — real provider delivery timestamps,
  not arrival order. Added as NEW overloads (existing 1-/2-/3-arg signatures unchanged and still
  always apply, matching pre-TASK-19.3 behavior) so none of the 40+ pre-existing webhook tests
  needed to change.

**Tests**: `StripePaymentProviderTest` (new, 4), `StripeWebhookServiceTest` (+3: stale event
rejected, newer event applies + advances the clock, no-timestamp overload still always applies),
`RazorpayWebhookServiceTest` (+2: same two cases).

## TASK 19.4 — Billing/entitlement divergence guard [P1]

New `BillingReconciliationJob` (`@Scheduled` daily 03:30 + `@SchedulerLock`, after
`PiiKeyRotationJob`/`OrganizationPurgeJob`'s slots): for every org with a linked provider
subscription, fetches the provider's LIVE status (new `PaymentProvider.fetchRemoteStatus`,
implemented for both Stripe and Razorpay) and compares it — mapped through the SAME
`mapStatus`/`mapStatus`-equivalent logic a real webhook sync would use, exposed as public statics
on both webhook services specifically so this comparison isn't a second, independently-invented
notion of equivalence — against the local `OrgSubscription.status`. Catches the class of drift no
webhook-driven sync can ever catch itself: a missed/failed webhook delivery, or a bug in this app.
Does NOT auto-correct (19.4.b) — alerts via `OpsAlertService` with org id, provider subscription
id, local state, and provider's raw state (19.4.c), one alert per RUN summarizing every divergence
found (not one per org), same convention `PiiKeyRotationJob`/`DunningScheduler` already use so a
bad run doesn't storm the single ops alert channel.

**Tests**: `BillingReconciliationJobTest` (5: divergence alerts with full detail, agreement is
silent, no-linked-subscription skips without a provider call, an unmappable/pre-activation
provider status is not treated as a divergence, a per-org fetch failure doesn't abort the rest of
the run).

## TASK 19.5 — Invoice immutability [P1]

`platform_invoices` is NOT append-only the way `unified_audit_events`/`ptp_audit_logs` are (the
SAME row legitimately transitions open→paid, or →void, as the webhook services keep it in step) —
cannot reuse `fn_audit_log_immutable()`'s blanket "any UPDATE fails" the way V084/V085 did, that
would break the very upsert this table exists for. New migration V102: a conditional trigger
(`fn_platform_invoice_immutable`) that blocks changes to financial/identity fields
(status/amounts/currency/paid_at/provider_invoice_id/provider/org_id) ONLY once the row is already
in a terminal state (paid/void/uncollectible), and only when the incoming value actually differs —
a same-value webhook redelivery (Stripe/Razorpay's own retry semantics) stays a safe no-op.
Non-financial fields (`hosted_invoice_url`/`invoice_pdf_url`) stay freely updatable even once
terminal — a provider refreshing a document URL isn't a correction of the financial record.
`invoice_line_items` (SYSTEM 19's GST line items) has no legitimate update path at all
(`GstInvoiceLineItemServiceImpl` only ever INSERTs) — reuses `fn_audit_log_immutable()` directly,
same convention V084 used for settlement/allocation audit logs.

**Tests**: added to `AuditLogImmutabilityTest` (the established real-DB immutability-trigger test
file) — `invoiceLineItem_insertSucceeds_updateAndDeleteAreRejected`,
`platformInvoice_onceTerminal_financialFieldRejectedButNoOpAndNonFinancialFieldsAllowed`,
`platformInvoice_openRow_freelyTransitionsToTerminal`.

## A real, previously-unknown production bug found while writing TASK 19.5's test

Writing a genuine integration test against the actual migrated schema (not a mocked unit test) —
a plain `INSERT INTO platform_invoices` — failed with **"new row violates row-level security
policy"**. This should have been impossible: `V054`'s own header comment explicitly documents why
this table must never have RLS ("the writer is the Stripe webhook, which arrives with no JWT and
therefore no tenant context ... An isolation policy would block the very writes this table exists
to record"). `V058` (a later, broader "close every remaining RLS gap" sweep) added RLS to
`platform_invoices` AND `org_subscriptions` anyway, with the STRICT form (no
`current_org_id() IS NULL` escape hatch) — its own comment shows the author was reasoning about a
different, narrower question (is a ROW's `organization_id` being NULL a deliberate platform-wide
row or a data bug), not "does this SESSION have any tenant context at all."

Traced the actual consequence rather than assuming one: `RlsContextFilter` only ever sets
`RlsOrgIdHolder` from an authenticated `SecurityContext`'s `UserPrincipal` — a webhook request
carries no JWT, so `current_org_id()` is NULL for the ENTIRE connection for the whole webhook
request, including the very first read that would tell the app which org a row belongs to. There
is no point in request handling where the app could set the right org id first, because it cannot
know which org until after it has already been blocked from reading the row that would tell it.
Confirmed this also silently affected pre-existing code that predates this session:
`DunningScheduler.expireSubscription()`'s `subRepo.save(sub)` and `OrganizationPurgeJob`'s
`orgSubscriptionRepository.delete(sub)` both run with no HTTP request (a scheduled-job thread), so
`RlsContextFilter` never runs for them either — same silent-failure exposure.

**Fixed via new migration V103**: restored `OR current_org_id() IS NULL` on both policies — the
SAME fail-open shape V010's original tenant-scoped-table policies (`allocations`, etc.) already
use, not a new or weaker pattern. Safe for `org_subscriptions`' genuinely tenant-scoped self-service
read path (`SubscriptionController`) because an authenticated request always has a real, non-null
org id from `RlsContextFilter` — the fail-open branch can only ever activate when there is truly no
session/tenant context at all, which only happens for webhooks and background jobs.
`user_creation_requests`/`lucien_chat_sessions`/`lucien_chat_messages` (the other three tables V058
gave the same strict policy) were deliberately NOT touched — no code path writes to them without an
authenticated session's org context, so they don't have this specific problem, but this was
pattern-matched, not independently re-verified table by table; flagged as worth a follow-up check
if a future session finds another webhook/background write into a strict-RLS table.
