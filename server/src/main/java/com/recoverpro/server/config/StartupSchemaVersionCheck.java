package com.recoverpro.server.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * SYSTEM 03 TASK 3.2: with {@code spring.flyway.enabled=false} (the production default, per
 * {@code application-prod.properties} -- migrations run as an explicit pre-deploy step, see
 * {@code docs/RUNBOOK-DEPLOY.md}), nothing else in this app checks whether the database schema
 * the app is about to run against actually matches what the build expects. Booting anyway against
 * a stale schema is a subtle runtime failure waiting to happen (a missing column, a query that
 * silently returns wrong results) instead of a loud, immediate one. This is that check.
 * <p>
 * Deliberately builds its OWN {@link Flyway} instance rather than depending on Spring Boot's
 * auto-configured {@code Flyway} bean -- that bean's very existence, and whether it has already
 * run, both depend on {@code spring.flyway.enabled}, which is exactly the setting this check needs
 * to work correctly regardless of. With migrations enabled (local/CI), Spring Boot's own Flyway
 * migration has already run by the time any {@code @PostConstruct} fires (it's ordered ahead of
 * other DataSource-dependent beans), so this check sees zero pending migrations and passes
 * silently -- a no-op confirmation, not a redundant migration attempt (this instance never calls
 * {@code .migrate()}, only {@code .info()}).
 * <p>
 * {@code @Profile("!local")}: the "local" profile (H2 in-memory, {@code
 * spring.jpa.hibernate.ddl-auto=create-drop}, {@code application-local.properties}) generates its
 * schema from JPA entity mappings directly and deliberately never runs Flyway at all -- there is no
 * "behind the build" concept for it, and this check would otherwise refuse to start it every time
 * (confirmed directly: it broke {@code ServerApplicationTest} before this exclusion was added).
 */
@Slf4j
@Component
@Profile("!local")
public class StartupSchemaVersionCheck {

    private final DataSource dataSource;

    public StartupSchemaVersionCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void checkSchemaVersion() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfoService info = flyway.info();
        MigrationInfo current = info.current();
        MigrationInfo[] pending = info.pending();

        log.info("Database schema version: {}",
                current != null ? current.getVersion() : "(no migrations applied yet)");

        if (pending.length > 0) {
            String pendingVersions = Arrays.stream(pending)
                    .map(m -> m.getVersion().toString())
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "Database schema is behind what this build expects. Refusing to start: "
                            + pending.length + " pending migration(s) not yet applied ["
                            + pendingVersions + "]. Run migrations as the explicit pre-deploy step "
                            + "described in docs/RUNBOOK-DEPLOY.md before starting this instance.");
        }
    }
}
