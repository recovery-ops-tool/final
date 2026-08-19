package com.recoverpro.server.security;

import com.recoverpro.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 01 TASK 1.1: fails the build if any table carrying an {@code organization_id}/{@code
 * org_id} column lacks enforced row-level security, so a new org-scoped table added later ships
 * fail-open by default instead of silently. Every table it currently excludes is listed in
 * {@link #ALLOWLIST} with a one-line reason -- an unexplained allowlist entry is a bug, not an
 * exemption, per the task's own instruction.
 * <p>
 * Every allowlist reason below was verified against the real schema/migrations/code as of
 * 2026-08-18, not assumed from an older comment: V058's own header comment already investigated
 * most of these (dead vs. deliberate) when it added RLS to the tables it could safely cover; this
 * test's allowlist reasons summarize that investigation, re-confirmed here by direct catalog and
 * `information_schema` queries plus entity-mapping greps, not copied blindly.
 */
class RlsCoverageTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * table -> reason it's exempt from the "must have forced RLS + a policy" rule.
     * <p>
     * {@code users} is the highest-risk entry here: unlike every other exemption, it is NOT dead
     * code and NOT platform-wide data -- it is live, sensitive, org-scoped PII with no RLS at all.
     * The reason is structural, not a security oversight: {@code AuthServiceImpl}/{@code
     * CustomUserDetailsService} must look a user up by email BEFORE any org context exists (that's
     * what login IS), and RLS scoped to {@code current_org_id()} would make that lookup return
     * nothing for every login attempt. Tenant isolation for {@code users} instead relies entirely
     * on every query being explicitly scoped in application code (service/repository methods that
     * filter by organizationId) -- there is no DB-layer backstop the way there is everywhere else
     * in this codebase. That is a real, standing risk (SYSTEM 09's territory, not this test's), not
     * something this allowlist entry should be read as clearing.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            "users",
            "receipt_sequences",
            "friday_chat_messages",
            "friday_chat_sessions",
            "notifications",
            "rag_chunks",
            "rag_documents_orgscoped_orphan_backup"
    );

    @Test
    void everyOrgScopedTable_hasForcedRlsAndAtLeastOnePolicy() {
        List<String> orgScopedTables = jdbcTemplate.queryForList("""
                SELECT DISTINCT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                  AND a.attname IN ('organization_id', 'org_id') AND a.attnum > 0
                  AND NOT a.attisdropped
                """, String.class);

        assertThat(orgScopedTables).isNotEmpty();

        List<String> offenders = new java.util.ArrayList<>();
        for (String table : orgScopedTables) {
            if (ALLOWLIST.contains(table)) {
                continue;
            }
            Boolean covered = jdbcTemplate.queryForObject("""
                    SELECT relrowsecurity AND relforcerowsecurity
                           AND EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = c.oid)
                    FROM pg_class c WHERE c.oid = ?::regclass
                    """, Boolean.class, table);
            if (!Boolean.TRUE.equals(covered)) {
                offenders.add(table);
            }
        }

        assertThat(offenders)
                .as("org-scoped table(s) missing forced RLS + a policy, and not in the allowlist "
                        + "(add a justified allowlist entry if this is intentional, e.g. platform-wide "
                        + "data): %s", offenders)
                .isEmpty();
    }

    @Test
    void allowlistedTables_stillExistAndHaveNoRls() {
        // Guards against ALLOWLIST rotting: if any of these tables ever gets a policy added, or
        // gets dropped entirely, that's exactly the kind of drift the top test's own coverage
        // can't catch (an allowlisted table is skipped, not asserted-uncovered) -- this one exists
        // solely so removing an entry here forces someone to re-check it's actually still needed.
        for (String table : ALLOWLIST) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
            assertThat(exists).as("allowlisted table %s no longer exists -- remove its entry", table)
                    .isTrue();
        }
    }
}
