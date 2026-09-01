package eu.nordtal.s2.updater;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.arcane.Arcane;
import eu.nordtal.s2.updater.config.Configs;
import eu.nordtal.s2.updater.config.DatabaseSpec;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.plan.Report;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.run.Runs;
import eu.nordtal.s2.updater.schema.RunLock;
import eu.nordtal.s2.updater.schema.Schema;
import eu.nordtal.s2.updater.serve.PostgresNotifications;
import eu.nordtal.s2.updater.serve.Runner;
import eu.nordtal.s2.updater.serve.UpdateServer;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Entry point.
 *
 * <h2>Four commands</h2>
 * <pre>
 *   updater            resolve, compare, print, exit. Touches nothing a server reads.
 *   updater migrate    apply the database schema, and nothing else.
 *   updater apply      resolve, print, migrate, fetch the files and move them into place.
 *   updater serve      migrate, then wait for requests from Discord and from in game.
 * </pre>
 *
 * <p>The default is the read-only one, deliberately: a container started by accident, or with an
 * argument that was misspelled, must do the harmless thing. Everything that writes has to be asked
 * for by name.</p>
 *
 * <p>{@code migrate} exists on its own because the schema is the one thing a deployment needs
 * before anything else can start - this container is the bootstrap, not a tool used on a running
 * one. {@code apply} does it too, before it moves a single jar, so a plugin never comes up against
 * a schema older than itself.</p>
 *
 * <h2>{@code serve} is the container that runs all the time, and it is not a scheduler</h2>
 * It applies the schema once, at startup, and then does <b>nothing at all</b> until somebody writes
 * a row into {@code update_request}. There is no timer, no watch and no "check for updates on
 * boot": the first rule of this module is that a crash restart at three in the morning does not
 * move a version, and a container that comes back up comes back on exactly the jars it was running.
 *
 * <p>The startup migration is not a version move and does not break that rule - the schema applied
 * is whatever <em>this</em> jar carries, and this jar is what it was. It is done there because the
 * updater is the only process that migrates and the whole stack starts at once after a redeploy;
 * {@code deploy/compose.yml} makes every other service wait for the readiness marker this writes
 * once the schema is current.</p>
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

    /** The long-running mode: the schema, then the request loop. */
    private static final String SERVE = "serve";

    /**
     * Touched once the schema is current and the loop is about to start.
     * <p>
     * {@code deploy/compose.yml}'s healthcheck is {@code test -f} on this path, and every other
     * service waits for it. A file rather than a port because this process does not serve one, and
     * in {@code /tmp} rather than a volume because it must be false again after a restart - a
     * readiness marker that survives the process it describes is worse than none.
     * </p>
     */
    private static final Path READY_MARKER = Path.of("/tmp/updater-ready");

    private UpdaterMain() {
    }

    public static void main(final String[] args) {
        final Path configDirectory = Path.of(
                System.getenv().getOrDefault("NORDTAL_UPDATER_CONFIG_DIR", DEFAULT_CONFIG_DIR));

        final int status = switch (command(args)) {
            // The schema, on its own. Nothing else is read - not updater.yml, not the network - so
            // a bootstrap run works against a host that has no release published yet.
            case MIGRATE -> migrate(configDirectory) ? 0 : 1;
            case SERVE -> serve(configDirectory);
            case APPLY -> apply(configDirectory);
            default -> report(configDirectory);
        };
        System.exit(status);
    }

    // ---------------------------------------------------------------- the read-only run

    private static int report(final Path configDirectory) {
        final UpdaterSpec config = updaterConfig(configDirectory);
        if (config == null) {
            return 1;
        }
        // stdout, not the logger: this is the program's output, not a record of it running. A
        // report wrapped in timestamps and thread names is a report nobody pastes anywhere.
        System.out.println(Report.render(Runs.resolve(config)));
        return 0;
    }

    // ---------------------------------------------------------------- the one that writes

    /**
     * Resolve, migrate, install - on the host, on demand.
     *
     * <p>This is the bootstrap command and the manual escape hatch, and it does not write a row
     * into {@code update_request}: on a fresh deployment the table does not exist until the
     * migration this run performs, so a request row would have to be written half way through its
     * own run. The daemon's requests are recorded; this one is recorded in whoever's shell history
     * it was typed into.</p>
     */
    private static int apply(final Path configDirectory) {
        final UpdaterSpec config = updaterConfig(configDirectory);
        final DatabaseSpec databaseConfig = databaseConfig(configDirectory);
        if (config == null || databaseConfig == null) {
            return 1;
        }

        try (Database database = Schema.open(databaseConfig)) {
            final Optional<RunLock> lock;
            try {
                lock = RunLock.tryAcquire(database.dataSource());
            } catch (final java.sql.SQLException failure) {
                log.error("Could not reach the database to take the updater lock. Nothing was done.",
                        failure);
                return 1;
            }
            if (lock.isEmpty()) {
                log.error("Another updater run is in progress - almost certainly the `updater`"
                        + " service, working on a request from Discord or from in game. Nothing was"
                        + " done. Wait for it to finish and run this again.");
                return 1;
            }

            try (RunLock held = lock.get()) {
                final UpdatePlan plan = Runs.resolve(config);
                System.out.println(Report.render(plan));

                // Before a single jar moves, and this order is the design: a plugin must never come
                // up against a schema older than it is. A migration that fails stops the run here -
                // nothing is fetched, nothing is written, and a half-migrated database with new
                // jars on top of it is the state nobody can reason about.
                try {
                    Schema.migrate(database);
                } catch (final RuntimeException failure) {
                    log.error("The database schema could not be applied. Nothing else was done.",
                            failure);
                    return 1;
                }

                final ApplyResult result = Runs.apply(config, plan);
                System.out.println(Report.render(result));
                return result.hasFailures() ? 1 : 0;
            }
        }
    }

    // ---------------------------------------------------------------- the one that stays

    private static int serve(final Path configDirectory) {
        final UpdaterSpec config = updaterConfig(configDirectory);
        final DatabaseSpec databaseConfig = databaseConfig(configDirectory);
        if (config == null || databaseConfig == null) {
            return 1;
        }

        try (Database database = Schema.open(databaseConfig)) {
            try {
                Schema.migrate(database);
            } catch (final RuntimeException failure) {
                // Refusing to come up is right here. Everything else in this deployment waits for
                // the readiness marker below, so a server that would have started against a schema
                // this build does not know simply does not start - which is the outcome the whole
                // arrangement exists to produce.
                log.error("The database schema could not be applied, so this container will not"
                        + " become ready. Nothing else in the stack starts until it does.", failure);
                return 1;
            }

            final Arcane arcane = new Arcane(config.arcane());
            if (!arcane.configured()) {
                log.warn("arcane.base-url is empty, so nothing here can restart the network."
                        + " Everything else works; a restart is a click in Arcane.");
            } else {
                log.info("Restarts go to {}", arcane.endpoint());
            }

            markReady();

            try (UpdateServer server = new UpdateServer(
                    UpdateDirectory.using(database.dataSource()),
                    new Runner(config, database, arcane),
                    PostgresNotifications.connector(databaseConfig),
                    Duration.ofSeconds(config.pollIntervalSeconds()),
                    Clock.systemUTC())) {

                // SIGTERM is how a redeploy asks; without this the container is killed after the
                // grace period instead of putting its pool down.
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "updater-shutdown"));
                server.serve();
            }
        }
        return 0;
    }

    private static void markReady() {
        try {
            Files.writeString(READY_MARKER, "ready\n");
        } catch (final IOException failure) {
            // Not fatal to this process, but fatal to everything waiting on it - so it is loud.
            log.error("Could not write the readiness marker {}. The rest of the stack will not"
                    + " start, because its healthcheck is a test for this file.", READY_MARKER,
                    failure);
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * The subcommand, or the empty string. Anything unrecognised reads as the default rather than
     * as an error: the default is the run that cannot break anything, and refusing to start over a
     * typo would mean a person retries - possibly with the typo fixed into {@code apply}.
     */
    private static String command(final String[] args) {
        return args.length == 0 ? "" : args[0].strip().toLowerCase(Locale.ROOT);
    }

    private static UpdaterSpec updaterConfig(final Path configDirectory) {
        try {
            return Configs.updater(configDirectory, LoggerFactory.getLogger(Configs.class)).get();
        } catch (final ConfigException broken) {
            // Named file, named setting, no stack trace: this is the one error an operator is
            // expected to fix, and a 40-line trace above the sentence is how it gets missed.
            log.error("Refusing to run on a config that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    private static DatabaseSpec databaseConfig(final Path configDirectory) {
        try {
            return Configs.database(configDirectory, LoggerFactory.getLogger(Configs.class)).get();
        } catch (final ConfigException broken) {
            log.error("Refusing to touch the database on a config that cannot be read: {}",
                    broken.getMessage());
            return null;
        }
    }

    /**
     * Applies the schema on its own. {@code false} means the run must not continue.
     * <p>
     * The two failures are told apart because they need different people: a config this module
     * refuses is an edit to {@code database.yml}, and a migration that fails is a look at the SQL
     * Flyway names in its own message.
     * </p>
     */
    private static boolean migrate(final Path configDirectory) {
        final DatabaseSpec database = databaseConfig(configDirectory);
        if (database == null) {
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
