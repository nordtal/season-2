package eu.nordtal.s2.common.access;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Applies every migration on the classpath to a test database, the location the worker migrates from.
 *
 * Flyway is a test dependency here and must never reach a Paper plugin.
 */
public final class AccessSchema {

    /** The same location jcore's {@code Database#migrate()} scans without arguments. */
    private static final String MIGRATIONS = "classpath:db/migration";

    private AccessSchema() {}

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
