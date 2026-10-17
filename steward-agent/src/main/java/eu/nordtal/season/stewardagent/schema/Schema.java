package eu.nordtal.season.stewardagent.schema;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.settings.Database;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;

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
     * Reads each service role's password from {@code NORDTAL_STEWARD_AGENT_DATABASE_<ROLE>_PASSWORD}.
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
        return "NORDTAL_STEWARD_AGENT_DATABASE_" + role.name() + "_PASSWORD";
    }

    /** The version the database's schema is at, or none when it could not be read or has no history yet. */
    public static @Nullable String version(final Database database) {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL"
                                + " ORDER BY installed_rank DESC LIMIT 1")) {
            return row.next() ? row.getString(1) : null;
        } catch (final SQLException unread) {
            log.warn("Could not read the schema's version: {}", unread.getMessage());
            return null;
        }
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
}
