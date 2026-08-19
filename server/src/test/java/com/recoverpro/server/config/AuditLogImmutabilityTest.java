package com.recoverpro.server.config;

import com.recoverpro.server.AbstractIntegrationTest;
import com.recoverpro.server.entity.Allocation;
import com.recoverpro.server.entity.AllocationAuditLog;
import com.recoverpro.server.entity.AuditEvent;
import com.recoverpro.server.entity.FileUpload;
import com.recoverpro.server.entity.InvoiceLineItem;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.PlatformInvoice;
import com.recoverpro.server.entity.SettlementAuditLog;
import com.recoverpro.server.entity.SettlementOffer;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.entity.UserActionAuditLog;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.AuditSeverity;
import com.recoverpro.server.enums.AuditSource;
import com.recoverpro.server.enums.FileUploadStatus;
import com.recoverpro.server.enums.OrganizationType;
import com.recoverpro.server.enums.PaymentProviderType;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.repository.AllocationAuditLogRepository;
import com.recoverpro.server.repository.AllocationRepository;
import com.recoverpro.server.repository.AuditEventRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.InvoiceLineItemRepository;
import com.recoverpro.server.repository.PlatformInvoiceRepository;
import com.recoverpro.server.repository.SettlementAuditLogRepository;
import com.recoverpro.server.repository.SettlementOfferRepository;
import com.recoverpro.server.repository.UserActionAuditLogRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves every append-only audit table's immutability trigger actually blocks UPDATE and DELETE
 * (and that INSERT is unaffected), across all three DB-level mechanisms in use:
 * trg_settlement_audit_immutable / trg_allocation_audit_immutable (V084) and
 * trg_unified_audit_events_immutable (V085) share {@code fn_audit_log_immutable()} (V006);
 * user_action_audit_logs' trg_audit_immutable / {@code prevent_audit_log_update()} (V016,
 * recreated by V028) is a separate function with a different message. See the shared helper below
 * for how the assertions handle that difference.
 * <p>
 * Deliberately NOT wrapped in a rolled-back Spring test transaction. {@link RlsOrgIdHolder} only
 * takes effect on the next JDBC connection checkout ({@link RlsAwareDataSource}); a single outer
 * transaction would hold one connection for the whole test, so a later {@code set()} call would
 * be a no-op against that connection's already-stamped session GUC. {@link RlsIsolationTest} uses
 * the same non-transactional, real-commit pattern for the same reason.
 * <p>
 * Fixture rows (org/user/file upload/allocation/settlement offer, and the audit rows themselves)
 * are intentionally left in place rather than cleaned up in {@code @AfterEach}: the audit rows can
 * never be deleted by construction (that's the behavior under test), and settlement_audit_logs'
 * FK-RESTRICT constraints to settlement_offers/allocations/users mean none of their parent rows
 * can be deleted either as long as the audit row exists. That's an accepted trade-off for a real
 * immutability test against a dev database, not an oversight.
 */
class AuditLogImmutabilityTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private AllocationAuditLogRepository allocationAuditLogRepository;
    @Autowired private SettlementAuditLogRepository settlementAuditLogRepository;
    @Autowired private FileUploadRepository fileUploadRepository;
    @Autowired private AllocationRepository allocationRepository;
    @Autowired private SettlementOfferRepository settlementOfferRepository;
    @Autowired private UserActionAuditLogRepository userActionAuditLogRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private PlatformInvoiceRepository platformInvoiceRepository;
    @Autowired private InvoiceLineItemRepository invoiceLineItemRepository;

    @AfterEach
    void clearRlsContext() {
        RlsOrgIdHolder.clear();
    }

    /**
     * SYSTEM-PLAN 10.1: this table's fake hash chain (previousHash/rowHash/computeHash(), never
     * called) was deleted on the strength of this exact claim -- that
     * trg_user_action_audit_immutable (V006) already provides real tamper-evidence, so the fake
     * chain was redundant rather than a gap. Unlike the two tables below, that claim had never
     * actually been exercised in code before; only asserted in this class's own header comment.
     */
    @Test
    void userActionAuditLog_insertSucceeds_updateAndDeleteAreRejected() throws SQLException {
        // Unlike allocation_audit_logs, user_action_audit_logs has a real FK to users
        // (fk_user_action_audit_logs_user) -- a random UUID is rejected before the trigger is
        // ever reached, so a real user row is required here.
        Organization org = organizationRepository.save(Organization.builder()
                .name("audit-immutable-user-action-" + UUID.randomUUID())
                .code(("T" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20))
                .organizationType(OrganizationType.ORGANIZATION)
                .isActive(true)
                .lookupHashPepper(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .build());
        User user = userRepository.save(User.builder()
                .organizationId(org.getId())
                .email("it-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("Test1234!"))
                .firstName("Immutability")
                .lastName("Fixture")
                .enabled(true)
                .roles(Set.of())
                .build());

        UserActionAuditLog saved = userActionAuditLogRepository.save(UserActionAuditLog.builder()
                .userId(user.getId())
                .action("STATUS_CHANGED")
                .details("pre-tamper details")
                .build());
        assertThat(saved.getId()).isNotNull();

        assertUpdateAndDeleteRejected("user_action_audit_logs", "details", saved.getId());
    }

    @Test
    void allocationAuditLog_insertSucceeds_updateAndDeleteAreRejected() throws SQLException {
        // allocation_audit_logs carries no FK constraints (V056), so a random allocation/user id
        // is sufficient to prove the trigger fires -- referential validity isn't what's under test.
        AllocationAuditLog saved = allocationAuditLogRepository.save(AllocationAuditLog.builder()
                .allocationId(UUID.randomUUID())
                .action("STATUS_CHANGED")
                .performedBy(UUID.randomUUID())
                .previousValue("ACTIVE")
                .newValue("CLOSED")
                .build());
        assertThat(saved.getId()).isNotNull();

        assertUpdateAndDeleteRejected("allocation_audit_logs", "new_value", saved.getId());
    }

    @Test
    void settlementAuditLog_insertSucceeds_updateAndDeleteAreRejected() throws SQLException {
        // settlement_audit_logs has real FK constraints to settlement_offers/allocations/users
        // (V038, ON DELETE RESTRICT), so unlike allocation_audit_logs this needs a valid parent
        // chain: organization -> file_upload + user -> allocation -> settlement_offer.
        Organization org = organizationRepository.save(Organization.builder()
                .name("audit-immutable-settlement-" + UUID.randomUUID())
                .code(("T" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20))
                .organizationType(OrganizationType.ORGANIZATION)
                .isActive(true)
                .lookupHashPepper(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .build());

        User user = userRepository.save(User.builder()
                .organizationId(org.getId())
                .email("it-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("Test1234!"))
                .firstName("Immutability")
                .lastName("Fixture")
                .enabled(true)
                .roles(Set.of())
                .build());

        RlsOrgIdHolder.set(org.getId());

        FileUpload fileUpload = fileUploadRepository.save(FileUpload.builder()
                .organization(org)
                .originalFilename("fixture.csv")
                .contentType("text/csv")
                .fileSizeBytes(10L)
                .sha256Hash(UUID.randomUUID().toString().replace("-", ""))
                .uploadType(UploadType.ALLOCATION)
                .isHistoricalImport(false)
                .status(FileUploadStatus.COMPLETED)
                .isDeleted(false)
                .build());

        Allocation allocation = allocationRepository.save(Allocation.builder()
                .fileUpload(fileUpload)
                .organization(org)
                .loanNumber("LN-IMMUTABLE-" + UUID.randomUUID())
                .borrowerName("Immutability Test Borrower")
                .isDeleted(false)
                .build());

        SettlementOffer offer = settlementOfferRepository.save(SettlementOffer.builder()
                .organizationId(org.getId())
                .allocationId(allocation.getId())
                .outstandingAtOffer(new BigDecimal("10000.00"))
                .offeredAmount(new BigDecimal("8000.00"))
                .discountPct(new BigDecimal("20.00"))
                .tenorDays(30)
                .validityUntil(Instant.now().plusSeconds(86_400))
                .draftedByUserId(user.getId())
                .build());

        SettlementAuditLog saved = settlementAuditLogRepository.save(SettlementAuditLog.builder()
                .settlementOfferId(offer.getId())
                .allocationId(allocation.getId())
                .action("STATUS_CHANGED")
                .performedBy(user.getId())
                .previousStatus("DRAFT")
                .newStatus("PROPOSED")
                .build());
        assertThat(saved.getId()).isNotNull();

        assertUpdateAndDeleteRejected("settlement_audit_logs", "new_status", saved.getId());
    }

    /**
     * SYSTEM 10 verification command specifies a manual psql check against unified_audit_events
     * ("attempt UPDATE unified_audit_events SET reason='x' and confirm the trigger rejects it").
     * Automated here instead of run by hand once and forgotten: trg_unified_audit_events_immutable
     * (V085) reuses fn_audit_log_immutable() from V006, the same function
     * allocation_audit_logs/settlement_audit_logs use (V084) -- so this is expected to hit the
     * ORIGINAL "audit log is immutable: ..." message, not the V016/V028 one the userActionAuditLog
     * case above hits. The shared helper only asserts the common "immutable" substring, so it
     * doesn't matter which exact wording comes back.
     */
    @Test
    void unifiedAuditEvent_insertSucceeds_updateAndDeleteAreRejected() throws SQLException {
        // unified_audit_events' RLS USING clause (V085) is organization_id = current_org_id() OR
        // is_platform_admin -- unlike the "OR current_org_id() IS NULL" pattern elsewhere, there is
        // NO bypass for an unset GUC or a NULL organization_id row. A row with no org context set
        // is invisible to UPDATE/DELETE targeting (0 rows matched, no exception) rather than
        // reaching the trigger at all, so this needs a real org and RlsOrgIdHolder set to actually
        // exercise the trigger -- discovered by this test failing with "no throwable raised" against
        // an org-less row before this fixture was added.
        Organization org = organizationRepository.save(Organization.builder()
                .name("audit-immutable-unified-" + UUID.randomUUID())
                .code(("T" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20))
                .organizationType(OrganizationType.ORGANIZATION)
                .isActive(true)
                .lookupHashPepper(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .build());

        RlsOrgIdHolder.set(org.getId());

        AuditEvent saved = auditEventRepository.save(AuditEvent.builder()
                .organizationId(org.getId())
                .actorType(AuditActorType.SYSTEM)
                .action(AuditAction.AUTH_LOGIN_SUCCESS)
                .resourceType(AuditResourceType.USER)
                .severity(AuditSeverity.INFO)
                .result(AuditResult.SUCCESS)
                .source(AuditSource.SYSTEM)
                .reason("pre-tamper reason")
                .build());
        assertThat(saved.getId()).isNotNull();

        assertUpdateAndDeleteRejected("unified_audit_events", "reason", saved.getId());
    }

    /** SYSTEM 19 TASK 19.5: invoice_line_items has no legitimate update path at all
     *  (GstInvoiceLineItemServiceImpl only ever INSERTs) -- reuses fn_audit_log_immutable()
     *  directly, same convention V084 used for settlement/allocation audit logs. RLS on this
     *  table requires org context (see V086), unlike the platform_invoices tests below. */
    @Test
    void invoiceLineItem_insertSucceeds_updateAndDeleteAreRejected() throws SQLException {
        Organization org = organizationRepository.save(Organization.builder()
                .name("invoice-line-item-immutable-" + UUID.randomUUID())
                .code(("T" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20))
                .organizationType(OrganizationType.ORGANIZATION)
                .isActive(true)
                .lookupHashPepper(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .build());
        RlsOrgIdHolder.set(org.getId());

        PlatformInvoice invoice = platformInvoiceRepository.save(PlatformInvoice.builder()
                .orgId(org.getId())
                .provider(PaymentProviderType.STRIPE)
                .providerInvoiceId("in_immutable_" + UUID.randomUUID())
                .status("open")
                .currency("inr")
                .build());

        InvoiceLineItem saved = invoiceLineItemRepository.save(InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description("Pre-tamper line item")
                .unitAmount(10000L)
                .lineTotal(10000L)
                .currency("inr")
                .build());
        assertThat(saved.getId()).isNotNull();

        assertUpdateAndDeleteRejected("invoice_line_items", "description", saved.getId());
    }

    /**
     * SYSTEM 19 TASK 19.5: platform_invoices CANNOT reuse the blanket "any UPDATE fails" trigger
     * (unlike invoice_line_items above) -- the SAME row legitimately transitions open -> paid via
     * StripeWebhookService.upsertInvoice/RazorpayWebhookService.mirrorInvoice, so this proves the
     * conditional trigger's three real behaviors together: (1) a financial field on an
     * ALREADY-terminal row is rejected, (2) a same-value "redelivery" on that same row is a safe
     * no-op (proves webhook retry semantics still work), (3) a non-financial field
     * (hosted_invoice_url) stays freely updatable even once terminal.
     */
    @Test
    void platformInvoice_onceTerminal_financialFieldRejectedButNoOpAndNonFinancialFieldsAllowed() throws SQLException {
        Organization org = organizationRepository.save(Organization.builder()
                .name("platform-invoice-immutable-" + UUID.randomUUID())
                .code(("T" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20))
                .organizationType(OrganizationType.ORGANIZATION)
                .isActive(true)
                .lookupHashPepper(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .build());

        PlatformInvoice invoice = platformInvoiceRepository.save(PlatformInvoice.builder()
                .orgId(org.getId())
                .provider(PaymentProviderType.STRIPE)
                .providerInvoiceId("in_terminal_" + UUID.randomUUID())
                .status("paid")
                .amountDue(299900L)
                .amountPaid(299900L)
                .currency("inr")
                .build());

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE platform_invoices SET amount_paid = 1 WHERE id = ?")) {
                ps.setObject(1, invoice.getId());
                assertThatThrownBy(ps::executeUpdate).hasMessageContaining("immutable");
            } finally {
                conn.rollback();
            }

            // Same value as already stored -- a webhook redelivery of the identical event must
            // remain a safe no-op, not start failing once an invoice is terminal.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE platform_invoices SET amount_paid = 299900 WHERE id = ?")) {
                ps.setObject(1, invoice.getId());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            } finally {
                conn.commit();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE platform_invoices SET hosted_invoice_url = 'https://example.test/new' WHERE id = ?")) {
                ps.setObject(1, invoice.getId());
                assertThat(ps.executeUpdate())
                        .as("a document URL refresh is not a correction of the financial record")
                        .isEqualTo(1);
            } finally {
                conn.commit();
            }
        }
    }

    /** The normal, expected open -> paid lifecycle transition must still work -- this trigger only
     *  protects a row that is ALREADY terminal, not the transition that makes it so. */
    @Test
    void platformInvoice_openRow_freelyTransitionsToTerminal() throws SQLException {
        Organization org = organizationRepository.save(Organization.builder()
                .name("platform-invoice-open-" + UUID.randomUUID())
                .code(("T" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20))
                .organizationType(OrganizationType.ORGANIZATION)
                .isActive(true)
                .lookupHashPepper(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .build());

        PlatformInvoice invoice = platformInvoiceRepository.save(PlatformInvoice.builder()
                .orgId(org.getId())
                .provider(PaymentProviderType.STRIPE)
                .providerInvoiceId("in_open_" + UUID.randomUUID())
                .status("open")
                .amountDue(299900L)
                .amountPaid(0L)
                .currency("inr")
                .build());

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE platform_invoices SET status = 'paid', amount_paid = 299900 WHERE id = ?")) {
                ps.setObject(1, invoice.getId());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            } finally {
                conn.commit();
            }
        }
    }

    /**
     * Attempts UPDATE then DELETE against the given committed row, each in its own explicit JDBC
     * transaction (rolled back immediately after, whether it throws or not), and asserts the
     * immutability trigger rejects both. {@code table}/{@code updateColumn} are hardcoded literals
     * from the call sites above, never external input.
     *
     * <p>Asserted message substring is just "immutable" (lowercase), not a longer phrase: this
     * table's trigger turned out to raise a DIFFERENT message than the other two.
     * user_action_audit_logs' trigger is {@code trg_audit_immutable}/{@code prevent_audit_log_update()}
     * (V016, recreated by V028's rebuild) — "%s rows are immutable" — not
     * {@code fn_audit_log_immutable()} (V006) — "audit log is immutable: %s on %s is not
     * permitted" — which allocation_audit_logs/settlement_audit_logs actually use (V084). V016's
     * partitioning rebuild replaced V006's original trigger on this one table; discovered by this
     * test actually running against the real message, not assumed from reading migrations in
     * isolation. Both are real, working, DB-level immutability triggers -- only the message and
     * the specific function differ.
     */
    private void assertUpdateAndDeleteRejected(String table, String updateColumn, UUID id) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + table + " SET " + updateColumn + " = 'TAMPERED' WHERE id = ?")) {
                ps.setObject(1, id);
                assertThatThrownBy(ps::executeUpdate).hasMessageContaining("immutable");
            } finally {
                conn.rollback();
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                ps.setObject(1, id);
                assertThatThrownBy(ps::executeUpdate).hasMessageContaining("immutable");
            } finally {
                conn.rollback();
            }
        }
    }
}
