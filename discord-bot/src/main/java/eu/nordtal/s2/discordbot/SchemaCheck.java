package eu.nordtal.s2.discordbot;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;

/**
 * The bot's answer to "is the schema the one I was built against?" - asked at startup, answered
 * before a Discord session exists.
 *
 * <h2>Why the bot stopped migrating, 2026-09-01</h2>
 * {@code AccessBot} carried the only {@code migrate()} call in this repository, and every plugin's
 * class comment said it never migrates. That worked, and it was held together by an operator rule
 * written in prose - <i>"after a schema change, bring the bot up first"</i> - which is a rule that
 * holds until the deployment where somebody does it in the other order. The call moved to the
 * {@code updater} module, because a release that adds a table is a release that adds a migration:
 * the schema and the versions are one thing and now have one owner.
 * See {@code docs/updater.md} and {@code docs/architecture.md#schema-ownership}.
 *
 * <h2>What is left here, and why it is not nothing</h2>
 * Without a check, a bot started against a database the updater has not migrated fails on its first
 * query - somewhere inside a Discord interaction, minutes later, as {@code relation "..." does not
 * exist}. This turns that into a refusal at startup with a sentence naming the command to run.
 *
 * <p>Flyway's own {@code validate()} is the check: it compares the migrations on this jar's
 * classpath - {@code :common} is shaded in, so they are the same files the updater applies -
 * against what the database says has been applied, and fails on a resolved migration that is not
 * there. It is the exact question, and it costs one query at startup.</p>
 *
 * <h2>The plugins do not do this, on purpose</h2>
 * Validating needs Flyway, and <b>Flyway must never be shaded into a Paper plugin</b> - a plugin jar
 * carrying a few KB of SQL text is fine, one carrying Flyway is not. The bot already has it through
 * jcore, so the check is free here and would be expensive anywhere else. A plugin against an old
 * schema still fails the way it always did, and the bot - which starts first - is what catches the
 * situation for the whole stack.
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
            // Resolved against this class's own class loader, the same way jcore's Database does
            // it, so the migrations bundled inside the shaded jar are found.
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
                            + "    docker compose --profile updater run --rm updater migrate\n\n"
                            + "Flyway said: " + invalid.getMessage(), invalid);
        }
        log.info("Database schema validated - it matches the migrations in this jar");
    }
}
