package eu.nordtal.s2.steward.worker.schema;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place in this deployment that applies the schema, from {@code classpath:db/migration}.
 *
 * A failed migration stops the run before any jar or pack moves.
 */
@Slf4j
public final class Schema {

    private Schema() {}

    /**
     * Applies every pending migration.
     *
     * @return how many were applied, zero on a current database
     * @throws org.flywaydb.core.api.FlywayException if a migration fails or the history is inconsistent, unwrapped
     */
    public static int migrate(final DatabaseSpec config) {
        try (Database database = open(config)) {
            return migrate(database);
        }
    }

    /**
     * Opens the pool that {@code serve} holds for as long as it runs.
     *
     * @return a pool the caller owns and must close
     */
    public static Database open(final DatabaseSpec config) {
        final Database database = Database.create(toDatabaseConfig(config));
        database.jdbi().installPlugin(Jdbis.ids());
        return database;
    }

    /**
     * Applies every pending migration over a pool somebody else owns.
     *
     * @return how many were applied, zero on a current database
     */
    public static int migrate(final Database database) {
        final int applied = database.migrate();
        if (applied == 0) {
            log.info("Schema is current - nothing to apply");
        } else {
            log.info("Applied {} database migration(s)", applied);
        }
        return applied;
    }

    private static DatabaseConfig toDatabaseConfig(final DatabaseSpec config) {
        return DatabaseConfig.builder(config.jdbcUrl())
                .username(config.username())
                .password(config.password())
                .poolName("steward-worker")
                .maximumPoolSize(config.maximumPoolSize())
                .connectionTimeout(Duration.ofSeconds(config.queryTimeoutSeconds()))
                .build();
    }
}
