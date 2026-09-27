package eu.nordtal.s2.discordbot;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;

/**
 * Refuses to start against a database this jar was not built against.
 *
 * steward-worker migrates; without this the bot would fail on its first query, inside a Discord interaction.
 */
@Slf4j
final class SchemaCheck {

    private SchemaCheck() {}

    /**
     * @throws IllegalStateException if the database is not at this jar's schema; the message names the worker's command
     */
    static void validate(final DataSource dataSource) {
        try {
            // Resolved against this class's own class loader, so the migrations bundled in the shaded jar are found.
            Flyway.configure(SchemaCheck.class.getClassLoader())
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .validate();
        } catch (final RuntimeException invalid) {
            throw new IllegalStateException(
                    "The database schema is not the one this bot was built against, so it is not"
                            + " starting. The bot does not apply migrations any more - steward-worker"
                            + " does. Run it against this stack:\n\n"
                            + "    docker compose run --rm steward-worker migrate\n\n"
                            + "Flyway said: " + invalid.getMessage(),
                    invalid);
        }
        log.info("Database schema validated - it matches the migrations in this jar");
    }
}
