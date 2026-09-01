package eu.nordtal.s2.common.access;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Applies the real season 2 schema to a test database - every migration on the classpath, not just
 * the access ones it was named after.
 * <p>
 * The migration files live in this module ({@code common/src/main/resources/db/migration}),
 * alongside the API that reads those tables. Exactly one process migrates at runtime and since
 * 2026-09-01 it is the {@code updater} container, not the bot. Flyway is a test dependency here and
 * nothing more - it must never reach a Paper plugin.
 * </p>
 * <p>
 * The location is the classpath rather than a source directory on purpose: it is byte for byte
 * the location {@code Database#migrate()} uses in the updater, so these tests also prove that the
 * migrations are reachable the way the updater reaches them.
 * </p>
 */
public final class AccessSchema {

    /** The same location jcore's {@code Database#migrate()} scans without arguments. */
    private static final String MIGRATIONS = "classpath:db/migration";

    private AccessSchema() {
    }

    /**
     * @param dataSource the test database to migrate
     */
    public static void migrate(final DataSource dataSource) {
        Flyway.configure(AccessSchema.class.getClassLoader())
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .load()
                .migrate();
    }
}
