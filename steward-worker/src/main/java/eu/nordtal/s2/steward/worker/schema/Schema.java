package eu.nordtal.s2.steward.worker.schema;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;

/**
 * The one place in this deployment that applies the schema, from {@code classpath:db/migration}.
 *
 * A failed migration stops the run before any jar or pack moves.
 */
@Slf4j
public final class Schema {

    private Schema() {}

    /**
     * Creates the services' roles with the passwords this deployment gives them, then applies every pending migration.
     *
     * @return how many were applied, zero on a current database
     * @throws org.flywaydb.core.api.FlywayException if a migration fails or the history is inconsistent, unwrapped
     * @throws IllegalStateException if the roles could not be created, or a password is missing
     */
    public static int migrate(final Database database, final Map<DatabaseRole, String> passwords) {
        try {
            DatabaseRole.provision(database.dataSource(), DatabaseRole.PREFIX, passwords);
        } catch (final SQLException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "The database roles could not be created: " + failure.getMessage(), failure);
        }
        return migrate(database);
    }

    /**
     * Reads each service role's password from {@code NORDTAL_STEWARD_DATABASE_<ROLE>_PASSWORD}.
     *
     * Environment only: a secret of another service has no place in this process's config file.
     */
    public static Map<DatabaseRole, String> passwords(final Map<String, String> environment) {
        final Map<DatabaseRole, String> passwords = new EnumMap<>(DatabaseRole.class);
        for (final DatabaseRole role : DatabaseRole.values()) {
            final String value = environment.get(passwordVariable(role));
            if (role.hasPassword() && value != null) {
                passwords.put(role, value);
            }
        }
        return passwords;
    }

    /** Returns the variable a role's password is read from. */
    public static String passwordVariable(final DatabaseRole role) {
        return "NORDTAL_STEWARD_DATABASE_" + role.name() + "_PASSWORD";
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
     * Applies every pending migration over a pool somebody else owns, with the roles already created.
     *
     * @return how many were applied, zero on a current database
     */
    public static int migrate(final Database database) {
        final int applied = Flyway.configure(Schema.class.getClassLoader())
                .dataSource(database.dataSource())
                .locations("classpath:db/migration")
                .placeholders(DatabaseRole.placeholders(DatabaseRole.PREFIX))
                .load()
                .migrate()
                .migrationsExecuted;
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
