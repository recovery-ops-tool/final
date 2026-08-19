package com.recoverpro.server.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class RlsDataSourceConfig {

    /**
     * SYSTEM 02 TASK 2.4: the app's single DataSource bean is now a routing datasource choosing
     * between a primary and a replica pool, per {@link ReplicaRoutingContext}. Both underlying
     * pools are wrapped in {@link RlsAwareDataSource} BEFORE being handed to the router -- this is
     * the task's own explicitly-flagged most-dangerous step; getting the wrapping order backwards
     * (routing first, RLS second, or wrapping only one target) would leave one path with no tenant
     * isolation at all. With {@code REPLICA_DB_URL} unset, the replica pool points at the exact
     * same URL as primary (see the property default below), so behavior is unchanged from before
     * this task -- verified in {@code ReplicaDataSourceRlsTest}.
     */
    @Bean
    @Primary
    public DataSource dataSource(
            DataSourceProperties properties,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:5}") int minimumIdle,
            @Value("${spring.datasource.hikari.connection-timeout:30000}") long connectionTimeoutMs,
            @Value("${REPLICA_DB_URL:${spring.datasource.url}}") String replicaUrl) {

        HikariDataSource primaryHikari = buildHikari(properties, properties.getUrl(),
                maximumPoolSize, minimumIdle, connectionTimeoutMs, "primary");
        RlsAwareDataSource rlsPrimary = new RlsAwareDataSource(primaryHikari);

        // REPLICA_DB_URL unset (the default everywhere until a real replica is provisioned --
        // see docs/INFRA-CURRENT.md) resolves to the exact same URL as primary. Reuse the SAME
        // pool for both routing targets in that case rather than opening a second, pointless
        // Hikari pool against the identical database -- true "byte-identical" behavior, not just
        // identical query results with double the idle connections sitting on the DB.
        RlsAwareDataSource rlsReplica;
        if (replicaUrl.equals(properties.getUrl())) {
            rlsReplica = rlsPrimary;
        } else {
            HikariDataSource replicaHikari = buildHikari(properties, replicaUrl,
                    maximumPoolSize, minimumIdle, connectionTimeoutMs, "replica");
            rlsReplica = new RlsAwareDataSource(replicaHikari);
        }

        ReplicaRoutingDataSource router = new ReplicaRoutingDataSource();
        router.setTargetDataSources(Map.of(
                ReplicaRoutingDataSource.PRIMARY, rlsPrimary,
                ReplicaRoutingDataSource.REPLICA, rlsReplica));
        router.setDefaultTargetDataSource(rlsPrimary);
        router.afterPropertiesSet();
        return router;
    }

    private HikariDataSource buildHikari(
            DataSourceProperties properties, String url,
            int maximumPoolSize, int minimumIdle, long connectionTimeoutMs, String poolName) {
        HikariDataSource hikari = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .url(url)
                .build();
        hikari.setPoolName(poolName);
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setMinimumIdle(minimumIdle);
        hikari.setConnectionTimeout(connectionTimeoutMs);
        return hikari;
    }
}
