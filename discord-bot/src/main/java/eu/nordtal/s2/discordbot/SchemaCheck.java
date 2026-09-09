package eu.nordtal.s2.discordbot;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;

/**
 * The bot refuses to start against a database it was not built against. The updater owns the
 * migrations; without this check the bot would instead fail on its first query, minutes later,
 * inside a Discord interaction.
 *
 * <p>Flyway's {@code validate()} compares the migrations shaded into this jar - the same files the
 * updater applies - against what the database says has been applied. The Paper plugins do not do
 * this, because Flyway must never be shaded into a plugin jar; the bot starts first and catches it
 * for the whole stack.</p>
 */
@Slf4j
final class SchemaCheck {

    private SchemaCheck() {
    }

    /**
     * @throws IllegalStateException if the database is not at the schema this jar expects. The
     *                               message names the updater command, because that is the only
     *                               thing that fixes it.
     */
    static void validate(final @NotNull DataSource dataSource) {
        try {
            // Resolved against this class's own class loader, so the migrations bundled inside
            // the shaded jar are found.
            Flyway.configure(SchemaCheck.class.getClassLoader())
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .validate();
        } catch (final RuntimeException invalid) {
            throw new IllegalStateException(
                    "The database schema is not the one this bot was built against, so it is not"
                            + " starting. The bot does not apply migrations any more - the updater"
                            + " does. Run it against this stack:\n\n"
                            + "    docker compose run --rm updater migrate\n\n"
                            + "Flyway said: " + invalid.getMessage(), invalid);
        }
        log.info("Database schema validated - it matches the migrations in this jar");
    }
}
