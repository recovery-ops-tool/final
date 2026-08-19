package com.recoverpro.server.config;

import com.recoverpro.server.AbstractIntegrationTest;
import com.recoverpro.server.entity.AuditEvent;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.enums.AuditSeverity;
import com.recoverpro.server.enums.AuditSource;
import com.recoverpro.server.repository.AuditEventRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 01 TASK 1.2: a partitioned table's row-security policy on the PARENT does not, by itself,
 * protect the partitions when one is queried directly by name -- Postgres does not propagate
 * row-security enforcement onto partitions for direct access; each partition needs its own
 * ENABLE/FORCE ROW LEVEL SECURITY and its own copy of the policy. Proven empirically before
 * V096 was written (a hand-run probe: org B's session could see org A's row via
 * {@code unified_audit_events_2026_08} directly, while the parent correctly hid it) -- this test is
 * the automated, permanent form of that same probe, against V096's fix.
 * <p>
 * Not wrapped in a rolled-back Spring test transaction, for the same reason as
 * {@link RlsIsolationTest} / {@link AuditLogImmutabilityTest}: {@link RlsOrgIdHolder} only takes
 * effect on the next JDBC connection checkout, so a single outer transaction would hold one
 * connection for the whole test and make a later {@code set()} call a no-op.
 */
class RlsPartitionIsolationTest extends AbstractIntegrationTest {

    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("yyyy_MM").withZone(ZoneOffset.UTC);

    @AfterEach
    void clearRlsContext() {
        RlsOrgIdHolder.clear();
    }

    @Test
    void directPartitionQuery_stillEnforcesOrgIsolation() {
        Organization orgA = createOrg("rls-partition-a");
        Organization orgB = createOrg("rls-partition-b");

        RlsOrgIdHolder.set(orgA.getId());
        AuditEvent saved = auditEventRepository.save(AuditEvent.builder()
                .organizationId(orgA.getId())
                .actorType(AuditActorType.SYSTEM)
                .action(AuditAction.AUTH_LOGIN_SUCCESS)
                .resourceType(AuditResourceType.USER)
                .severity(AuditSeverity.INFO)
                .result(AuditResult.SUCCESS)
                .source(AuditSource.SYSTEM)
                .reason("partition-isolation-probe")
                .build());
        assertThat(saved.getId()).isNotNull();

        String currentPartition = "unified_audit_events_" + MONTH_FMT.format(Instant.now());

        RlsOrgIdHolder.set(orgB.getId());
        Long visibleToOrgBViaPartition = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + currentPartition + " WHERE id = ?",
                Long.class, saved.getId());
        assertThat(visibleToOrgBViaPartition)
                .as("org B must not see org A's row through the partition table directly")
                .isZero();

        RlsOrgIdHolder.set(orgA.getId());
        Long visibleToOrgAViaPartition = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + currentPartition + " WHERE id = ?",
                Long.class, saved.getId());
        assertThat(visibleToOrgAViaPartition)
                .as("org A must still see its own row through the partition table directly")
                .isEqualTo(1L);
    }

    /**
     * Catalog-level companion to the functional test above: every existing partition of
     * unified_audit_events must actually carry the policy V096 added, not just the one this test
     * happens to insert into.
     */
    @Test
    void everyUnifiedAuditEventsPartition_hasForcedRlsAndAPolicy() {
        var uncovered = jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'public.unified_audit_events'::regclass
                  AND (NOT c.relrowsecurity
                       OR NOT c.relforcerowsecurity
                       OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))
                """, String.class);

        assertThat(uncovered)
                .as("every unified_audit_events partition must have forced RLS and a policy")
                .isEmpty();
    }
}
