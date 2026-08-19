package com.recoverpro.server.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * SYSTEM 02 TASK 2.4: picks between the primary and replica DataSource per connection checkout,
 * based on {@link ReplicaRoutingContext}. Both targets are ALREADY wrapped in
 * {@link RlsAwareDataSource} before being registered here (see {@link RlsDataSourceConfig}) --
 * this class only ever sees already-RLS-safe targets, so routing to either one still stamps
 * {@code app.current_org_id} correctly. Never register a bare, unwrapped DataSource as a target.
 */
class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

    static final String PRIMARY = "primary";
    static final String REPLICA = "replica";

    @Override
    protected Object determineCurrentLookupKey() {
        return ReplicaRoutingContext.isReplica() ? REPLICA : PRIMARY;
    }
}
