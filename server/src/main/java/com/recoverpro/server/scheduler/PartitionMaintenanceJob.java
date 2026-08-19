package com.recoverpro.server.scheduler;

import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Period;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates upcoming monthly partitions for every RANGE-partitioned audit table on the 25th of
 * each month, several months ahead of need, so a missed job run does not immediately push rows
 * into a table's DEFAULT partition.
 *
 * ShedLock prevents duplicate execution in multi-pod deployments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionMaintenanceJob {

    private final JdbcTemplate jdbcTemplate;
    private final OpsAlertService opsAlertService;
    private final AppProperties appProperties;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final DateTimeFormatter DATE_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Every RANGE-partitioned-by-month audit table this job is responsible for. */
    static final List<String> PARTITIONED_TABLES = List.of(
            "user_action_audit_logs",
            "unified_audit_events"
    );

    private record PartitionRlsPolicy(String policyName, String usingExpr) {}

    /**
     * SYSTEM 01 TASK 1.2: a partitioned table's row-security policy does NOT protect its
     * partitions when queried directly by name (proven empirically -- see V096's header comment).
     * Each partition needs the same policy applied to it individually. Only tables that already
     * have a real org-scoped parent-level policy are listed here -- {@code user_action_audit_logs}
     * has no organization_id column and no parent-level policy to mirror, so it is deliberately
     * absent (see V096's header comment; this is a separate, larger, flagged gap, not an oversight).
     */
    private static final Map<String, PartitionRlsPolicy> PARTITION_RLS_POLICY = Map.of(
            "unified_audit_events",
            new PartitionRlsPolicy(
                    "rls_unified_audit_events_isolation",
                    "organization_id = current_org_id() "
                            + "OR current_setting('app.is_platform_admin', true) = 'true'")
    );

    /** How many months ahead to ensure partitions exist for, so one missed run isn't fatal. */
    private static final int LOOKAHEAD_MONTHS = 3;

    /** Runs at 02:00 on the 25th of every month. */
    @Scheduled(cron = "0 0 2 25 * *")
    @SchedulerLock(name = "partition_maintenance", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void createUpcomingPartitions() {
        List<String> failures = new ArrayList<>();
        // SYSTEM 13 TASK 13.2: createPartition() below already logs at ERROR and alerts with full
        // context per-partition, so this isn't a silent swallow in the "nobody ever finds out"
        // sense -- but the exception itself was discarded here, so the summary exception thrown
        // at the end had no cause chain at all, making root-cause tracing from that exception
        // alone impossible. Keep the first real cause so the final exception actually points back
        // to it instead of originating fresh with nothing behind it.
        Exception firstCause = null;

        for (String table : PARTITIONED_TABLES) {
            for (int monthsAhead = 1; monthsAhead <= LOOKAHEAD_MONTHS; monthsAhead++) {
                YearMonth month = YearMonth.now().plusMonths(monthsAhead);
                try {
                    createPartition(table, month);
                } catch (Exception e) {
                    failures.add(table + "_" + month.format(MONTH_FMT));
                    if (firstCause == null) firstCause = e;
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "PartitionMaintenance: failed to create partitions: " + failures, firstCause);
        }
    }

    private void createPartition(String table, YearMonth month) {
        String partitionName = table + "_" + month.format(MONTH_FMT);
        String fromDate = month.atDay(1).format(DATE_FMT);
        String toDate   = month.plusMonths(1).atDay(1).format(DATE_FMT);

        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, table, fromDate, toDate);

        try {
            jdbcTemplate.execute(sql);
            log.info("PartitionMaintenance: created partition {} ({} → {})", partitionName, fromDate, toDate);
            applyRlsIfApplicable(table, partitionName);
        } catch (Exception e) {
            log.error("PartitionMaintenance: failed to create partition {}: {}", partitionName, e.getMessage());
            opsAlertService.alertJobFailure("PartitionMaintenanceJob.createUpcomingPartitions",
                    "partition=" + partitionName + " (audit-log inserts will start failing once "
                            + fromDate + " arrives without this partition)", e);
            throw e;
        }
    }

    /**
     * SYSTEM 10 TASK 10.3: retention horizon per partitioned table, sourced from
     * {@link AppProperties.Audit} so it's configurable without a redeploy of this class. See
     * docs/AUDIT-RETENTION.md for the signed-off numbers (2026-08-19: 7 years / 24 months) and why
     * partition-drop can only have one horizon per table, not per audit category.
     */
    private Map<String, Period> retentionHorizons() {
        AppProperties.Audit audit = appProperties.getAudit();
        Map<String, Period> horizons = new LinkedHashMap<>();
        horizons.put("unified_audit_events", Period.ofYears(audit.getUnifiedAuditEventsRetentionYears()));
        horizons.put("user_action_audit_logs", Period.ofMonths(audit.getUserActionAuditLogsRetentionMonths()));
        return horizons;
    }

    /**
     * Runs alongside {@link #createUpcomingPartitions()} on the same 25th-of-the-month partition-
     * maintenance schedule (TASK 10.3.c: "add the purge to the same scheduler as 10.2"), offset 15
     * minutes later under its own {@code @SchedulerLock} name so a lock or failure on one method
     * never blocks or masks the other -- creating next month's partitions and purging years-old
     * ones are unrelated failure domains sharing one job class, not one operation.
     * <p>
     * Dry-run by default ({@link AppProperties.Audit#isRetentionPurgeEnabled()}): a retention job
     * that drops the wrong partition is unrecoverable (DROP TABLE, not DELETE -- nothing to roll
     * back), so every eligible partition is logged either way, and only actually dropped when the
     * flag is explicitly on.
     */
    @Scheduled(cron = "0 15 2 25 * *")
    @SchedulerLock(name = "partition_retention_purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeExpiredPartitions() {
        try {
            doPurgeExpiredPartitions();
        } catch (Exception e) {
            log.error("PartitionMaintenance retention: purge pass failed: {}", e.getMessage(), e);
            opsAlertService.alertJobFailure("PartitionMaintenanceJob.purgeExpiredPartitions",
                    "audit-partition retention purge failed -- old partitions may not be getting "
                            + "dropped, or (if enabled) a drop may have partially failed", e);
            throw e;
        }
    }

    private void doPurgeExpiredPartitions() {
        boolean purgeEnabled = appProperties.getAudit().isRetentionPurgeEnabled();
        for (Map.Entry<String, Period> entry : retentionHorizons().entrySet()) {
            String table = entry.getKey();
            YearMonth cutoff = YearMonth.now().minus(entry.getValue());
            for (String partition : listPartitions(table)) {
                YearMonth partitionMonth = parsePartitionMonth(table, partition);
                // Never touch anything that doesn't cleanly parse as this table's own
                // "<table>_yyyy_MM" naming convention -- most importantly, the DEFAULT partition
                // (named "<table>_default"), which is never eligible for purge: it isn't a bounded
                // month, and dropping it would discard whatever backdated/out-of-range rows landed
                // there regardless of age.
                if (partitionMonth == null || !partitionMonth.isBefore(cutoff)) {
                    continue;
                }
                long approxRowCount = approxRowCount(partition);
                if (!purgeEnabled) {
                    log.info("PartitionMaintenance retention (DRY RUN, would drop): partition={} "
                                    + "month={} cutoff={} approxRows={} -- set "
                                    + "app.audit.retention-purge-enabled=true to actually drop",
                            partition, partitionMonth, cutoff, approxRowCount);
                    continue;
                }
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + partition);
                log.info("PartitionMaintenance retention: dropped partition={} month={} cutoff={} approxRows={}",
                        partition, partitionMonth, cutoff, approxRowCount);
            }
        }
    }

    private List<String> listPartitions(String table) {
        return jdbcTemplate.queryForList(
                "SELECT c.relname FROM pg_inherits i "
                        + "JOIN pg_class c ON c.oid = i.inhrelid "
                        + "JOIN pg_class p ON p.oid = i.inhparent "
                        + "WHERE p.relname = ?",
                String.class, table);
    }

    private YearMonth parsePartitionMonth(String table, String partitionName) {
        String prefix = table + "_";
        if (!partitionName.startsWith(prefix)) {
            return null;
        }
        try {
            return YearMonth.parse(partitionName.substring(prefix.length()), MONTH_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code pg_class.reltuples}, not {@code SELECT count(*)}: unified_audit_events' partitions
     * carry FORCE RLS (TASK 10.2's {@link #applyRlsIfApplicable}), and this job's own connection
     * has no org context and no platform-admin bypass -- it's a system-wide sweep across every
     * org's rows in a partition, not one admin reading one tenant's data, so
     * {@code PlatformAdminAccessGuard}'s bypass (which requires attributing access to a single
     * target org) is the wrong tool here, not just an inconvenient one. A direct count would
     * silently return 0 for every unified_audit_events partition under RLS with no error to reveal
     * it. {@code reltuples} is planner-estimate accuracy, not exact, but is catalog metadata (not
     * subject to RLS at all) and good enough for an informational log line -- a partition already
     * past its retention horizon is cold (no recent writes), so its estimate is stable in practice.
     */
    private long approxRowCount(String partitionName) {
        Long estimate = jdbcTemplate.queryForObject(
                "SELECT reltuples::bigint FROM pg_class WHERE oid = quote_ident(?)::regclass",
                Long.class, partitionName);
        return estimate != null ? Math.max(estimate, 0) : 0;
    }

    private void applyRlsIfApplicable(String table, String partitionName) {
        PartitionRlsPolicy policy = PARTITION_RLS_POLICY.get(table);
        if (policy == null) {
            return;
        }
        jdbcTemplate.execute(String.format("ALTER TABLE %s ENABLE ROW LEVEL SECURITY", partitionName));
        jdbcTemplate.execute(String.format("ALTER TABLE %s FORCE ROW LEVEL SECURITY", partitionName));
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_policy WHERE polrelid = (quote_ident(?))::regclass",
                Integer.class, partitionName);
        if (existing != null && existing == 0) {
            jdbcTemplate.execute(String.format(
                    "CREATE POLICY %s ON %s USING (%s)",
                    policy.policyName(), partitionName, policy.usingExpr()));
        }
        log.info("PartitionMaintenance: applied RLS to partition {}", partitionName);
    }
}
