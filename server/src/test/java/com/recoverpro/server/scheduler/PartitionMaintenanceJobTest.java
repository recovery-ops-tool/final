package com.recoverpro.server.scheduler;

import com.recoverpro.server.AbstractIntegrationTest;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.service.OpsAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK 10.2: proves PartitionMaintenanceJob creates upcoming partitions for BOTH RANGE-partitioned
 * audit tables (not just user_action_audit_logs, the pre-existing gap), by running the real job
 * against the real dev database and checking the partitions exist via {@code to_regclass}.
 * <p>
 * unified_audit_events already has explicit partitions through 2026-12 (V085) -- for those months
 * this job's {@code CREATE TABLE IF NOT EXISTS} is a deliberate no-op, which this test also covers
 * since the lookahead window can land inside that pre-existing range. user_action_audit_logs only
 * has explicit partitions through 2026-08 (V028), so this job creating the next
 * {@link PartitionMaintenanceJob#LOOKAHEAD_MONTHS}-worth for that table is a real, previously-absent
 * effect being verified here, not a no-op.
 * <p>
 * Deliberately drops only the partitions this test itself created (tracked in {@code createdByTest}),
 * never partitions that already existed before the job ran -- this must stay safe to run repeatedly
 * against a persistent local dev database, not a disposable one.
 */
class PartitionMaintenanceJobTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MM");

    @Autowired private PartitionMaintenanceJob partitionMaintenanceJobBean;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OpsAlertService opsAlertService;

    /**
     * The Spring-managed bean is ShedLock-proxied ({@code @SchedulerLock(..., lockAtLeastFor =
     * "PT1M")}), which is correct for production (prevents two instances re-running this within a
     * minute of each other) but means a SECOND direct call to {@code createUpcomingPartitions()}
     * within a minute of a FIRST one -- exactly what happens when both test methods in this class
     * call it -- silently no-ops instead of running the method body. Discovered when
     * {@code createUpcomingPartitions_reappliesRlsToUnifiedAuditEventsPartitions} passed in
     * isolation but failed whenever run alongside the other test in this class: the second call's
     * body never executed, so nothing re-applied the RLS this test had just stripped. Unwrapping
     * the AOP proxy to call the raw target bypasses ShedLock for these tests, which are exercising
     * the job's own logic, not its distributed-locking behavior (that's ShedLock's own concern).
     */
    private PartitionMaintenanceJob partitionMaintenanceJob;

    @BeforeEach
    void unwrapShedLockProxy() {
        partitionMaintenanceJob = AopTestUtils.getUltimateTargetObject(partitionMaintenanceJobBean);
    }

    private final List<String> createdByTest = new ArrayList<>();

    @AfterEach
    void dropPartitionsCreatedByTest() {
        for (String partition : createdByTest) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + partition);
        }
    }

    /**
     * SYSTEM 01 TASK 1.2: proves the JOB itself (not just migration V096) is what re-applies
     * per-partition RLS -- strips RLS from an already-existing unified_audit_events partition
     * first, so the only thing that can restore it is this test's call to the job.
     */
    @Test
    void createUpcomingPartitions_reappliesRlsToUnifiedAuditEventsPartitions() {
        String partitionName = "unified_audit_events_"
                + YearMonth.now().plusMonths(1).format(MONTH_FMT);

        jdbcTemplate.execute("DROP POLICY IF EXISTS rls_unified_audit_events_isolation ON " + partitionName);
        jdbcTemplate.execute("ALTER TABLE " + partitionName + " NO FORCE ROW LEVEL SECURITY");
        jdbcTemplate.execute("ALTER TABLE " + partitionName + " DISABLE ROW LEVEL SECURITY");

        Boolean strippedBeforeJobRuns = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE oid = ?::regclass",
                Boolean.class, partitionName);
        assertThat(strippedBeforeJobRuns).isFalse();

        partitionMaintenanceJob.createUpcomingPartitions();

        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ?::regclass",
                Boolean.class, partitionName);
        Integer policyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_policy WHERE polrelid = ?::regclass",
                Integer.class, partitionName);
        assertThat(enabled).as("job must re-enable+force RLS on %s", partitionName).isTrue();
        assertThat(policyCount).as("job must recreate the policy on %s", partitionName).isEqualTo(1);
    }

    @Test
    void createUpcomingPartitions_createsPartitionsForBothTables() {
        for (String table : PartitionMaintenanceJob.PARTITIONED_TABLES) {
            for (int monthsAhead = 1; monthsAhead <= 3; monthsAhead++) {
                String partitionName = table + "_" + YearMonth.now().plusMonths(monthsAhead).format(MONTH_FMT);
                Boolean existedBefore = jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, partitionName);
                if (Boolean.FALSE.equals(existedBefore)) {
                    createdByTest.add(partitionName);
                }
            }
        }

        partitionMaintenanceJob.createUpcomingPartitions();

        for (String table : PartitionMaintenanceJob.PARTITIONED_TABLES) {
            for (int monthsAhead = 1; monthsAhead <= 3; monthsAhead++) {
                String partitionName = table + "_" + YearMonth.now().plusMonths(monthsAhead).format(MONTH_FMT);
                Boolean exists = jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, partitionName);
                assertThat(exists)
                        .as("partition %s should exist after the job runs", partitionName)
                        .isTrue();
            }
        }
    }

    /**
     * SYSTEM 10 TASK 10.3: uses its own manually-constructed {@link PartitionMaintenanceJob}
     * instance (not the Spring-managed {@code partitionMaintenanceJobBean}) so each test can set
     * its own {@link AppProperties.Audit#setRetentionPurgeEnabled} without mutating the shared,
     * context-cached {@code AppProperties} bean other test classes also depend on.
     */
    private PartitionMaintenanceJob jobWithRetention(boolean purgeEnabled) {
        AppProperties props = new AppProperties();
        props.getAudit().setRetentionPurgeEnabled(purgeEnabled);
        return new PartitionMaintenanceJob(jdbcTemplate, opsAlertService, props);
    }

    private void createBarePartition(String table, YearMonth month) {
        String partitionName = table + "_" + month.format(MONTH_FMT);
        String fromDate = month.atDay(1).toString();
        String toDate = month.plusMonths(1).atDay(1).toString();
        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, table, fromDate, toDate));
        createdByTest.add(partitionName);
    }

    /** Mirrors {@link PartitionMaintenanceJob#applyRlsIfApplicable} so a test partition matches
     *  what TASK 10.2 actually applies to every real unified_audit_events partition -- proves the
     *  purge pass's row-count estimate (TASK 10.3's {@code approxRowCount}) works against a
     *  FORCE-RLS partition from a connection with no org context, the real scheduled-job shape. */
    private void applyUnifiedAuditEventsRls(String partitionName) {
        jdbcTemplate.execute("ALTER TABLE " + partitionName + " ENABLE ROW LEVEL SECURITY");
        jdbcTemplate.execute("ALTER TABLE " + partitionName + " FORCE ROW LEVEL SECURITY");
        jdbcTemplate.execute("CREATE POLICY rls_unified_audit_events_isolation ON " + partitionName
                + " USING (organization_id = current_org_id() "
                + "OR current_setting('app.is_platform_admin', true) = 'true')");
    }

    @Test
    void purgeExpiredPartitions_dryRunByDefault_logsButDropsNothing() {
        YearMonth ancientMonth = YearMonth.now().minusYears(30);
        String ancientPartition = "user_action_audit_logs_" + ancientMonth.format(MONTH_FMT);
        createBarePartition("user_action_audit_logs", ancientMonth);

        jobWithRetention(false).purgeExpiredPartitions();

        Boolean stillExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, ancientPartition);
        assertThat(stillExists)
                .as("dry-run (the default) must never drop a partition, no matter how far past its horizon")
                .isTrue();
    }

    @Test
    void purgeExpiredPartitions_enabled_dropsOnlyPartitionsPastTheirOwnHorizon() {
        // 30 years is past BOTH horizons (7y for unified_audit_events, 24mo for user_action_audit_logs).
        YearMonth ancientMonth = YearMonth.now().minusYears(30);
        String ancientUserActionPartition = "user_action_audit_logs_" + ancientMonth.format(MONTH_FMT);
        String ancientUnifiedPartition = "unified_audit_events_" + ancientMonth.format(MONTH_FMT);
        createBarePartition("user_action_audit_logs", ancientMonth);
        createBarePartition("unified_audit_events", ancientMonth);
        applyUnifiedAuditEventsRls(ancientUnifiedPartition);

        // The current month is well within either horizon and must survive regardless of table.
        String currentUserActionPartition = "user_action_audit_logs_" + YearMonth.now().format(MONTH_FMT);

        jobWithRetention(true).purgeExpiredPartitions();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NULL", Boolean.class, ancientUserActionPartition))
                .as("a user_action_audit_logs partition 30 years past its 24-month horizon must be dropped")
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NULL", Boolean.class, ancientUnifiedPartition))
                .as("a unified_audit_events partition 30 years past its 7-year horizon must be dropped "
                        + "even though it carries FORCE RLS and this job's connection has no org "
                        + "context -- proves approxRowCount's reltuples read doesn't get blocked or "
                        + "silently zeroed by RLS the way a direct count(*) would")
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, currentUserActionPartition))
                .as("a partition well within its horizon must survive a purge pass")
                .isTrue();
    }

    @Test
    void purgeExpiredPartitions_neverDropsTheDefaultPartition() {
        Boolean defaultExistsBefore = jdbcTemplate.queryForObject(
                "SELECT to_regclass('unified_audit_events_default') IS NOT NULL", Boolean.class);
        assertThat(defaultExistsBefore).as("precondition: the DEFAULT partition must exist").isTrue();

        jobWithRetention(true).purgeExpiredPartitions();

        Boolean defaultExistsAfter = jdbcTemplate.queryForObject(
                "SELECT to_regclass('unified_audit_events_default') IS NOT NULL", Boolean.class);
        assertThat(defaultExistsAfter)
                .as("the DEFAULT partition has no bounded month and must never be purge-eligible")
                .isTrue();
    }
}
