package com.recoverpro.server.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SYSTEM 03 TASK 3.2: real integration test, not mocked -- runs the actual
 * {@link StartupSchemaVersionCheck} against a genuinely separate, throwaway database, left
 * deliberately behind, to prove the check throws under the exact condition it exists to catch.
 * <p>
 * Needs a full separate DATABASE, not just a separate schema within {@code opstool}: an earlier
 * version of this test tried a same-database-different-schema approach and found a real, if
 * harmless-in-production, migration quirk -- V003's rename logic queries
 * {@code information_schema.tables} without a {@code table_schema} filter, so it "sees" the real
 * {@code public} schema's already-renamed {@code lucien_chat_sessions} from a different schema in
 * the same database and skips the rename in the test schema, which then fails a later step.
 * Never a real issue in production (there is only ever one schema, {@code public}), only an
 * artifact of that isolation strategy -- switched to a separate database instead of chasing it.
 * <p>
 * Requires CREATE DATABASE privilege, which this repo's local dev {@code opstool} role
 * deliberately does NOT have (SYSTEM 41 established the same restricted-role pattern for
 * {@code backup_agent}) -- CI's Postgres service container bootstraps {@code opstool} AS the
 * container's actual superuser instead (see {@code server-ci.yml}'s comment), so this test is
 * fully real there. Locally, it skips cleanly (not a red failure) if creating the database fails.
 */
class StartupSchemaVersionCheckTest {

    private static final String DB_NAME = "startup_schema_check_test";
    private static final String ADMIN_URL = "jdbc:postgresql://localhost:5432/" + jdbcAdminDbName();
    private static final String TEST_DB_URL = "jdbc:postgresql://localhost:5432/" + DB_NAME;

    private boolean databaseCreated = false;

    @BeforeEach
    void createScratchDatabase() {
        assumeTrue(System.getenv("DB_PASSWORD") != null,
                "DB_PASSWORD not set in the OS environment -- run `set -a && source .env && set +a` first");

        try (Connection conn = DriverManager.getConnection(ADMIN_URL, dbUser(), dbPassword());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB_NAME);
            st.execute("CREATE DATABASE " + DB_NAME);
            databaseCreated = true;
        } catch (SQLException e) {
            abort("Cannot create a scratch database with the current DB_USER/DB_PASSWORD "
                    + "(needs CREATE DATABASE privilege -- expected locally, see this class's "
                    + "javadoc): " + e.getMessage());
        }
    }

    @AfterEach
    void dropScratchDatabase() throws SQLException {
        if (!databaseCreated) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, dbUser(), dbPassword());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
        }
    }

    @Test
    void schemaBehindTheBuild_refusesToStart() {
        Flyway.configure()
                .dataSource(testDataSource())
                .locations("classpath:db/migration")
                .target("90") // deliberately behind -- current build has migrations well past V090
                .load()
                .migrate();

        StartupSchemaVersionCheck check = new StartupSchemaVersionCheck(testDataSource());

        assertThatThrownBy(check::checkSchemaVersion)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("pending migration");
    }

    @Test
    void schemaFullyMigrated_startsCleanly() {
        Flyway.configure()
                .dataSource(testDataSource())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        StartupSchemaVersionCheck check = new StartupSchemaVersionCheck(testDataSource());

        assertThatCode(check::checkSchemaVersion).doesNotThrowAnyException();
    }

    private DataSource testDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(TEST_DB_URL);
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        return ds;
    }

    private static String jdbcAdminDbName() {
        // Connect to the app's own database to issue CREATE/DROP DATABASE against -- avoids
        // assuming a "postgres" maintenance database exists under this role's visibility.
        return System.getenv().getOrDefault("DB_NAME_FOR_ADMIN_CONN", "opstool");
    }

    private static String dbUser() {
        return System.getenv().getOrDefault("DB_USER", "opstool");
    }

    private static String dbPassword() {
        return System.getenv("DB_PASSWORD");
    }
}
