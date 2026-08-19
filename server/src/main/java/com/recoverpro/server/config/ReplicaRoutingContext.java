package com.recoverpro.server.config;

import java.util.function.Supplier;

/**
 * SYSTEM 02 TASK 2.4: marks the current thread's NEXT connection checkout as a read-replica
 * candidate. {@link ReplicaRoutingDataSource#determineCurrentLookupKey()} reads this exactly once
 * per checkout, the same "ThreadLocal read at connection-acquisition time" pattern
 * {@link RlsOrgIdHolder} already uses for org scoping.
 * <p>
 * <b>Only safe around a call that acquires its own, independent transaction/connection</b> --
 * {@code AbstractRoutingDataSource} resolves the target DataSource once, when a transaction first
 * requests a connection, and does not re-route mid-transaction. Wrapping part of an
 * already-open, longer-lived transaction (e.g. mid-way through a {@code REQUIRES_NEW} method that
 * also writes) has no effect: the connection for that transaction was already bound before this
 * flag could matter. See {@code ExportServiceImpl.findReportJob} for a correct usage (a bare
 * repository call with no enclosing {@code @Transactional}, so each call gets its own fresh
 * checkout) and the SYSTEM 02 execution record for why {@code ReportJobExecutor.buildReportData}
 * is NOT wired to this despite being named in the task -- it runs inside the same
 * {@code REQUIRES_NEW} transaction as the job-status writes that follow it.
 */
public final class ReplicaRoutingContext {

    private static final ThreadLocal<Boolean> USE_REPLICA = new ThreadLocal<>();

    private ReplicaRoutingContext() {
    }

    static boolean isReplica() {
        return Boolean.TRUE.equals(USE_REPLICA.get());
    }

    /** Runs {@code action} with the replica flag set, always clearing it afterward. */
    public static <T> T runOnReplica(Supplier<T> action) {
        USE_REPLICA.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            USE_REPLICA.remove();
        }
    }

    public static void runOnReplica(Runnable action) {
        runOnReplica(() -> {
            action.run();
            return null;
        });
    }
}
