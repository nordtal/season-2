package eu.nordtal.s2.common.access;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.io.File;

/**
 * Applies the real access schema to a test database.
 * <p>
 * The migration lives in {@code access-bot/src/main/resources/db/migration} - the bot is the
 * only module that migrates - while the API that reads it lives here. Rather than keeping a second
 * copy of the DDL in this module's test resources, which would drift the first time a column
 * changes, these tests run the migration directory itself. Its absolute path is handed in by
 * {@code common/build.gradle.kts} as the {@code nordtal.test.migrations} system property.
 * </p>
 */
final class AccessSchema {

    private static final String MIGRATIONS_PROPERTY = "nordtal.test.migrations";

    private AccessSchema() {
    }

    static void migrate(final DataSource dataSource) {
        final String location = System.getProperty(MIGRATIONS_PROPERTY);
        if (location == null) {
            throw new IllegalStateException(
                    "System property " + MIGRATIONS_PROPERTY + " is not set - run these tests through Gradle");
        }

        final File directory = new File(location);
        if (!directory.isDirectory()) {
            throw new IllegalStateException("Migration directory does not exist: " + directory);
        }

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + directory.getAbsolutePath())
                .load()
                .migrate();
    }
}
