package eu.nordtal.s2.updater;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.updater.apply.Applier;
import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.config.Configs;
import eu.nordtal.s2.updater.config.DatabaseSpec;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.Http;
import eu.nordtal.s2.updater.http.Downloads;
import eu.nordtal.s2.updater.http.JdkHttp;
import eu.nordtal.s2.updater.plan.Report;
import eu.nordtal.s2.updater.plan.Resolver;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.source.GitHubReleases;
import eu.nordtal.s2.updater.source.Modrinth;
import eu.nordtal.s2.updater.schema.Schema;
import eu.nordtal.s2.updater.source.PaperFill;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Entry point.
 *
 <h2>Three commands</h2>
 * <pre>
 *   updater            resolve, compare, print, exit. Touches nothing a server reads.
 *   updater migrate    apply the database schema, and nothing else.
 *   updater apply      resolve, print, migrate, fetch the files and move them into place.
 * </pre>
 *
 * <p>The default is the read-only one, deliberately: a container started by accident, or with an
 * argument that was misspelled, must do the harmless thing. {@code apply} has to be asked for by
 * name.</p>
 *
 * <p>{@code migrate} exists on its own because the schema is the one thing a deployment needs
 * before anything else can start - this container is the bootstrap now, not a tool used on a
 * running one. {@code apply} does it too, before it moves a single jar, so a plugin never comes up
 * against a schema older than itself.</p>
 *
 * <p>{@code apply} still does not restart anything. It prints what it did and stops, which is the
 * whole reason the restart is a separate button in step 6 - a person reads the report first. Steps
 * 2, 4, 5 and 6 of docs/updater.md add the schema, the Discord surface, the Arcane redeploy and the
 * in-game command; until then this is a one-shot command and the container that runs it must never
 * be given a restart policy or a schedule.</p>
 *
 * <h2>Exit codes</h2>
 * {@code 0} when a report was produced, whatever the report says - including one full of rows that
 * could not be checked, because that <em>is</em> the answer and it is in the text. {@code 1} when
 * no report could be produced at all, which in practice means a config this module refuses, and
 * when an {@code apply} run had a failure in it - there the non-zero is earned: something was
 * attempted and did not work.
 * <p>
 * Deliberately not "non-zero when an update is available": that would make every scheduler treat a
 * pending update as a failure, and this module's first rule is that nothing updates on a schedule.
 * </p>
 */
@Slf4j
public final class UpdaterMain {

    /** Mirrors the bot's layout: WORKDIR /app, config in a volume at /app/config. */
    private static final String DEFAULT_CONFIG_DIR = "config";

    /** The one argument that makes this run write anything into a server's volume. */
    private static final String APPLY = "apply";

    /** The schema on its own - the first thing a deployment needs and the last thing to move. */
    private static final String MIGRATE = "migrate";

    private UpdaterMain() {
    }

    public static void main(final String[] args) {
        final Path configDirectory = Path.of(
                System.getenv().getOrDefault("NORDTAL_UPDATER_CONFIG_DIR", DEFAULT_CONFIG_DIR));

        final String command = command(args);

        // The schema, on its own. Nothing else is read - not updater.yml, not the network - so a
        // bootstrap run works against a host that has no release published yet.
        if (MIGRATE.equals(command)) {
            System.exit(migrate(configDirectory) ? 0 : 1);
            return;
        }

        final UpdaterSpec config;
        try {
            config = Configs.updater(configDirectory, LoggerFactory.getLogger(Configs.class)).get();
        } catch (final ConfigException broken) {
            // Named file, named setting, no stack trace: this is the one error an operator is
            // expected to fix, and a 40-line trace above the sentence is how it gets missed.
            log.error("Refusing to run on a config that cannot be read: {}", broken.getMessage());
            System.exit(1);
            return;
        }

        final Http http = new JdkHttp(Duration.ofSeconds(config.httpTimeoutSeconds()), config.githubToken());
        final Resolver resolver = new Resolver(
                config,
                new GitHubReleases(http),
                new Modrinth(http),
                new PaperFill(http),
                Clock.systemUTC());

        final UpdatePlan plan = resolver.resolve();

        // stdout, not the logger: this is the program's output, not a record of it running. A
        // report wrapped in timestamps and thread names is a report nobody pastes anywhere.
        System.out.println(Report.render(plan));

        if (!APPLY.equals(command)) {
            return;
        }

        // Before a single jar moves, and this order is the design: a plugin must never come up
        // against a schema older than it is. A migration that fails stops the run here - nothing
        // is fetched, nothing is written, and a half-migrated database with new jars on top of it
        // is the state nobody can reason about.
        if (!migrate(configDirectory)) {
            System.exit(1);
            return;
        }

        final ApplyResult result = new Applier(config,
                new Downloads(Duration.ofSeconds(config.downloadTimeoutSeconds()))).apply(plan);
        System.out.println(Report.render(result));

        if (result.hasFailures()) {
            System.exit(1);
        }
    }

    /**
     * The subcommand, or the empty string. Anything unrecognised reads as the default rather than
     * as an error: the default is the run that cannot break anything, and refusing to start over a
     * typo would mean a person retries - possibly with the typo fixed into {@code apply}.
     */
    private static String command(final String[] args) {
        return args.length == 0 ? "" : args[0].strip().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Applies the schema. {@code false} means the run must not continue.
     * <p>
     * The two failures are told apart because they need different people: a config this module
     * refuses is an edit to {@code database.yml}, and a migration that fails is a look at the SQL
     * Flyway names in its own message.
     * </p>
     */
    private static boolean migrate(final Path configDirectory) {
        final DatabaseSpec database;
        try {
            database = Configs.database(configDirectory, LoggerFactory.getLogger(Configs.class)).get();
        } catch (final ConfigException broken) {
            log.error("Refusing to migrate on a config that cannot be read: {}", broken.getMessage());
            return false;
        }

        try {
            Schema.migrate(database);
            return true;
        } catch (final RuntimeException failed) {
            // Flyway's own message names the file and the statement. Printed as it is, with the
            // cause, because this is the one error where the detail is the whole value.
            log.error("The database schema could not be applied. Nothing else was done.", failed);
            return false;
        }
    }
}
