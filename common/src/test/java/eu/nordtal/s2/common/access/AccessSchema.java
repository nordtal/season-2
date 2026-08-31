package eu.nordtal.s2.common.access;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Applies the real access schema to a test database.
 * <p>
 * The migration files live in this module ({@code common/src/main/resources/db/migration}),
 * alongside the API that reads those tables; the bot is still the only process that migrates at
 * runtime. Flyway is a test dependency here and nothing more.
 * </p>
 * <p>
 * The location is the classpath rather than a source directory on purpose: it is byte for byte
 * the location {@code Database#migrate()} uses in the bot, so these tests also prove that the
 * migrations are reachable the way the bot reaches them.
 * </p>
 */
final class AccessSchema {

    /** The same location jcore's {@code Database#migrate()} scans without arguments. */
    private static final String MIGRATIONS = "classpath:db/migration";

    private AccessSchema() {
    }

    static void migrate(final DataSource dataSource) {
        Flyway.configure(AccessSchema.class.getClassLoader())
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .load()
                .migrate();
    }
}
