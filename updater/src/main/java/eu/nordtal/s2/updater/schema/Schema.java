package eu.nordtal.s2.updater.schema;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.updater.config.DatabaseSpec;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * The one place in this deployment that applies the schema, since 2026-09-01.
 *
 * <h2>What moved and what did not</h2>
 * The <em>call</em> moved here from {@code AccessBot}, which was the only {@code migrate()} in the
 * repository. The <em>SQL</em> did not move: it stays in
 * {@code common/src/main/resources/db/migration/}, next to the API that reads it, and reaches this
 * classpath because {@code :common} is shaded into this module's jar - exactly how it reached the
 * bot's. jcore's {@code Database#migrate()} scans {@code classpath:db/migration}, so nothing about
 * how the files are found changed either.
 *
 * <h2>Why the updater and not the bot</h2>
 * A release that adds a table is a release that adds a migration. The schema and the versions are
 * one thing, so they get one owner - and the alternative was an operator rule written in prose
 * ("bring the bot up first, it is the only process that migrates"), which works until the
 * deployment where somebody does it in the other order and finds out from a stack trace.
 *
 * <h2>Migration comes before anything moves</h2>
 * {@code updater apply} migrates first and swaps jars afterwards, so a plugin never comes up
 * against a schema older than itself. A migration that fails stops the run: no jar is fetched, no
 * pack is written, and the report says why. That is the one outcome where this module must refuse
 * to do half a run - a half-migrated database with new jars on top of it is the state nobody can
 * reason about.
 *
 * <h2>The pool is opened and closed around one call</h2>
 * Every other module here keeps a pool for as long as it runs. This one exists for the length of a
 * migration, which is why it is a static method and not a field: there is nothing to hold.
 */
@Slf4j
public final class Schema {

    private Schema() {
    }

    /**
     * Applies every pending migration.
     *
     * @return how many were applied - zero is the ordinary answer on a database that is current.
     * @throws org.flywaydb.core.api.FlywayException if a migration fails or the history is
     *                                               inconsistent. Deliberately not wrapped: Flyway's
     *                                               own message names the file and the statement,
     *                                               and nothing this module could add would beat it.
     */
    public static int migrate(final @NotNull DatabaseSpec config) {
        try (Database database = open(config)) {
            return migrate(database);
        }
    }

    /**
     * Opens the pool.
     * <p>
     * The one-shot commands do not need this - {@link #migrate(DatabaseSpec)} opens and closes one
     * around a single call. {@code updater serve} does: it holds a pool for as long as it runs, an
     * advisory lock connection out of it for as long as an apply takes, and a {@code LISTEN}
     * connection <em>outside</em> it that pgjdbc opens directly.
     * </p>
     *
     * @return a pool the caller owns and must close
     */
    public static @NotNull Database open(final @NotNull DatabaseSpec config) {
        return Database.create(toDatabaseConfig(config));
    }

    /**
     * Applies every pending migration over a pool somebody else owns.
     *
     * @return how many were applied - zero is the ordinary answer on a database that is current
     */
    public static int migrate(final @NotNull Database database) {
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
                .poolName("updater")
                .maximumPoolSize(config.maximumPoolSize())
                .connectionTimeout(Duration.ofSeconds(config.queryTimeoutSeconds()))
                .build();
    }
}
