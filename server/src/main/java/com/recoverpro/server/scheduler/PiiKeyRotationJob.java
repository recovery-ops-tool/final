package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import com.recoverpro.server.security.encryption.EncryptionContext;
import com.recoverpro.server.security.encryption.EnvelopeEncryptor;
import com.recoverpro.server.security.encryption.LocalKeyEnvelopeEncryptor;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SYSTEM 07 TASK 7.2.c: after a local PII encryption key rotation (docs/RUNBOOK-KEY-ROTATION.md),
 * rows written under a retired key version stay readable (LocalKeyEnvelopeEncryptor keeps every
 * still-configured key loaded) but are never proactively upgraded to the current key just by
 * being read -- this job is what actually does that, so the old key can eventually be retired
 * from app.encryption.previous-keys-base64.
 *
 * No-ops entirely when the active encryptor isn't {@link LocalKeyEnvelopeEncryptor} -- KMS embeds
 * key identity in its own ciphertext blob and needs no equivalent (see
 * {@link EnvelopeEncryptor#needsRewrite}'s javadoc), and a disabled encryptor has nothing
 * encrypted to rewrite.
 *
 * <p>Scans one bounded batch per column per organization per run rather than the whole table --
 * a large rotation is expected to take several nightly runs to fully drain, not one. The old key
 * must stay in app.encryption.previous-keys-base64 until every column reports zero remaining rows
 * (docs/RUNBOOK-KEY-ROTATION.md has the verification query) -- retiring it early orphans whatever
 * this job hasn't reached yet (LocalKeyEnvelopeEncryptorTest#retiredKeyVersionRemovedFromConfig_...
 * covers what that failure mode looks like: a redacted placeholder per row, not a crash).
 *
 * <p>Loops per-organization and scopes RlsOrgIdHolder to each in turn, the same pattern
 * LookupHashBackfillRunner uses for the analogous cross-tenant HMAC-key backfill: this is a
 * headless maintenance job that never holds more than one org's data in scope at a time, so
 * PlatformAdminAccessGuard's audited cross-org bypass (built for attributing a human platform
 * admin's reads) doesn't apply here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiKeyRotationJob {

    private final JdbcTemplate jdbcTemplate;
    private final OrganizationRepository organizationRepository;
    private final OpsAlertService opsAlertService;

    private static final int BATCH_SIZE_PER_COLUMN = 500;

    private record EncryptedColumn(String table, String column, String idColumn) {}

    /** Every @Convert(EncryptedStringConverter.class) column in the schema, as of SYSTEM 07
     *  TASK 7.2 -- keep this in sync with the entities themselves; there is no reflection-based
     *  discovery here, so a newly-encrypted column added later needs a line added here too. */
    private static final List<EncryptedColumn> ENCRYPTED_COLUMNS = List.of(
            new EncryptedColumn("lucien_chat_sessions", "agent_first_name", "id"),
            new EncryptedColumn("visit_logs", "contact_person", "id"),
            new EncryptedColumn("visit_logs", "contact_number", "id"),
            new EncryptedColumn("visit_logs", "last_visited_address", "id"),
            new EncryptedColumn("visit_logs", "visit_notes", "id"),
            new EncryptedColumn("visit_logs", "internal_remarks", "id"),
            new EncryptedColumn("visit_logs", "gps_address", "id"),
            new EncryptedColumn("user_creation_requests", "requested_first_name", "id"),
            new EncryptedColumn("user_creation_requests", "requested_last_name", "id"),
            new EncryptedColumn("ptp_history", "changed_by_name", "id"),
            new EncryptedColumn("ptp_records", "agent_name", "id"),
            new EncryptedColumn("ptp_records", "borrower_name", "id"),
            new EncryptedColumn("users", "first_name", "id"),
            new EncryptedColumn("users", "last_name", "id"),
            new EncryptedColumn("users", "mfa_secret", "id"),
            new EncryptedColumn("allocations", "borrower_name", "id"),
            new EncryptedColumn("borrowers", "ckyc_id", "id"),
            new EncryptedColumn("borrowers", "first_name", "id"),
            new EncryptedColumn("borrowers", "last_name", "id"),
            new EncryptedColumn("borrowers", "email", "id"),
            new EncryptedColumn("borrowers", "phone", "id"),
            new EncryptedColumn("borrowers", "address", "id"),
            new EncryptedColumn("borrowers", "nominee_name", "id"),
            new EncryptedColumn("borrowers", "nominee_relation", "id"),
            new EncryptedColumn("borrowers", "nominee_phone", "id"),
            new EncryptedColumn("borrowers", "nominee_email", "id"),
            new EncryptedColumn("npa_records", "borrower_name", "id")
    );

    /** Runs at 01:30 daily -- off-peak, and a no-op fast SQL query (see reencryptColumn) whenever
     *  nothing is actually mid-rotation, so leaving this permanently scheduled costs nothing. */
    @Scheduled(cron = "0 30 1 * * *")
    @SchedulerLock(name = "pii_key_rotation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reencryptStaleRows() {
        EnvelopeEncryptor encryptor = EncryptionContext.encryptor();
        if (!(encryptor instanceof LocalKeyEnvelopeEncryptor local)) {
            return;
        }

        List<Organization> orgs = organizationRepository.findAll();
        int totalRewritten = 0;
        int orgFailures = 0;

        for (Organization org : orgs) {
            RlsOrgIdHolder.set(org.getId());
            try {
                for (EncryptedColumn col : ENCRYPTED_COLUMNS) {
                    totalRewritten += reencryptColumn(col, local);
                }
            } catch (Exception e) {
                orgFailures++;
                log.error("PiiKeyRotation: failed for orgId={}: {}", org.getId(), e.getMessage(), e);
            } finally {
                RlsOrgIdHolder.clear();
            }
        }

        if (totalRewritten > 0) {
            log.info("PiiKeyRotation: re-encrypted {} value(s) to key version {} across {} organization(s) this run",
                    totalRewritten, local.currentVersion(), orgs.size());
        }
        if (orgFailures > 0) {
            opsAlertService.alertJobFailure("PiiKeyRotationJob.reencryptStaleRows",
                    orgFailures + " organization(s) failed this run -- their pre-rotation-key rows "
                            + "are still readable (old key stays loaded), just not yet migrated", null);
        }
    }

    private int reencryptColumn(EncryptedColumn col, LocalKeyEnvelopeEncryptor local) {
        // NOT LIKE the current-version marker narrows the scan to rows that plausibly need
        // rewriting before any Java-side work -- needsRewrite() below is still the authoritative
        // check (handles the legacy-no-marker case this LIKE pattern can't distinguish cheaply),
        // this is purely so a batch of already-current rows doesn't consume the LIMIT budget.
        String currentVersionMarker = "enc:v1:kv" + local.currentVersion() + ":%";
        String selectSql = String.format(
                "SELECT %s AS row_id, %s AS row_value FROM %s WHERE %s LIKE 'enc:v1:%%' AND %s NOT LIKE ? LIMIT %d",
                col.idColumn(), col.column(), col.table(), col.column(), col.column(), BATCH_SIZE_PER_COLUMN);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, currentVersionMarker);
        int rewritten = 0;
        String updateSql = String.format("UPDATE %s SET %s = ? WHERE %s = ?", col.table(), col.column(), col.idColumn());

        for (Map<String, Object> row : rows) {
            String value = (String) row.get("row_value");
            if (!local.needsRewrite(value)) continue;
            String plaintext = local.decrypt(value);
            String reEncrypted = local.encrypt(plaintext);
            jdbcTemplate.update(updateSql, reEncrypted, row.get("row_id"));
            rewritten++;
        }
        return rewritten;
    }
}
