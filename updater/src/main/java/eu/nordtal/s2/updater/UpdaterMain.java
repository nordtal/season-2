package eu.nordtal.s2.updater;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.arcane.Arcane;
import eu.nordtal.s2.updater.config.Configs;
import eu.nordtal.s2.updater.config.DatabaseSpec;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.plan.Change;
import eu.nordtal.s2.updater.plan.Report;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.run.Runs;
import eu.nordtal.s2.updater.schema.RunLock;
import eu.nordtal.s2.updater.schema.Schema;
import eu.nordtal.s2.updater.schema.ServeLock;
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
 *   updater report     resolve, compare, print, exit. Touches nothing a server reads.
 *   updater migrate    apply the database schema, and nothing else.
 *   updater apply      resolve, print, migrate, fetch the files and move them into place.
 *   updater serve      migrate, then wait for requests from Discord and from in game.
 * </pre>
 *
 * <p>The default is the read-only one, deliberately: a container started by accident, or with an
 * argument that was misspelled, must do the harmless thing. Everything that writes has to be asked
 * for by name.</p>
 *
 * <p><b>{@code report} is also named, and that is not tidiness.</b> The default is unreachable from
 * the one command five documents told an operator to type: {@code docker compose run --rm updater}
 * inherits the service's {@code command} - {@code serve} - so the "prints what is installed and
 * changes nothing" run was in fact a second long-running daemon that migrated, bootstrapped and
 * listened, and a terminal that hung. Measured 2026-09-02, and it is the same when the service
 * carries no {@code command} at all: {@code run} then takes the image's {@code CMD}.</p>
 *
 * <p>{@code migrate} exists on its own because the schema is the one thing a deployment needs
 * before anything else can start - this container is the bootstrap, not a tool used on a running
 * one. {@code apply} does it too, before it moves a single jar, so a plugin never comes up against
 * a schema older than itself.</p>
 *
 * <h2>{@code serve} is the container that runs all the time, and it is not a scheduler</h2>
 * At startup it applies the schema and installs what is <em>missing</em>, and then does
 * <b>nothing at all</b> until somebody writes a row into {@code update_request}. There is no timer,
 * no watch and no "check for updates on boot": the first rule of this module is that a crash restart
 * at three in the morning does not move a version, and a container that comes back up comes back on
 * exactly the jars it was running.
 *
 * <p>Neither startup step breaks that rule, and it is worth being exact about why. The schema
 * applied is whatever <em>this</em> jar carries, and this jar is what it was. The install is
 * restricted to artefacts with nothing installed at all ({@link UpdatePlan#onlyMissing()}), so a
 * volume that already holds a jar keeps it however old it is - a restart of a live network finds
 * nothing missing and moves nothing. What the install is for is the other case: a brand new stack,
 * where every volume is empty and a Minecraft server refuses to start without plugins. That used to
 * need {@code updater apply} typed on the host, which is a thing Arcane cannot do.</p>
 *
 * <p>Both are done here because the updater is the only process that migrates and the whole stack
 * starts at once after a redeploy; {@code compose.yml} makes every other service wait for the
 * readiness marker this writes once the schema is current and the empty volumes are filled.</p>
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
     * The read-only run, by name.
     *
     * <h2>Why it needs a name when it is already the default</h2>
     * Because {@code docker compose run --rm updater} does not reach the default. Compose passes
     * the service's own {@code command} to a {@code run} that names none - and when the service
     * defines none it falls through to the image's {@code CMD} instead, which was measured on
     * 2026-09-02 and is true of both. Five places documented that bare command as the harmless
     * report; all five started a second {@code serve} daemon that migrated, bootstrapped and
     * listened, while the operator watched a terminal that never came back.
     *
     * <p>Anchoring {@code serve} in the image rather than in the service does not help, for exactly
     * the same reason. The only thing that makes a typed command do what it says is a name for what
     * it does.</p>
     */
    private static final String REPORT = "report";

    /**
     * Touched once the schema is current and the loop is about to start.
     * <p>
     * {@code compose.yml}'s healthcheck is {@code test -f} on this path, and every other
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
            // REPORT is named as well as defaulted: `docker compose run --rm updater` cannot reach
            // the default - it inherits the service's `command`, or the image's CMD when the
            // service names none. Anything unrecognised still lands here, which is the safe end.
            case REPORT -> report(configDirectory);
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
            // BEFORE ANYTHING ELSE, because everything below assumes it. UpdateServer.settleOrphans
            // closes every row left RUNNING on the reasoning that nothing can be running them - the
            // only process that claims one is an updater, and this one has just started. That is
            // true of one serve and false of two: a second one marks the first one's in-flight
            // APPLY as FAILED, the real one's finish() then matches no RUNNING row, and the report
            // of a run that was installing jars is lost. This makes the premise a fact.
            final Optional<ServeLock> serveLock;
            try {
                serveLock = ServeLock.acquire(database.dataSource());
            } catch (final java.sql.SQLException failure) {
                log.error("Could not reach the database to take the serve lock, so this container"
                        + " will not become ready.", failure);
                return 1;
            }
            if (serveLock.isEmpty()) {
                log.error("Another updater is already serving this database, and has been for longer"
                        + " than a redeploy takes to hand over. Refusing to start a second one:"
                        + " two serve loops settle each other's in-flight requests as failures and"
                        + " lose the report of whichever was actually working. If you meant the"
                        + " read-only report, that is `updater report`.");
                return 1;
            }

            try (ServeLock held = serveLock.get()) {
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

                // Fill empty volumes before anything is told this container is ready. See below for
                // why a failure here does NOT stop the readiness marker.
                if (config.bootstrap()) {
                    bootstrap(config, database);
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
        }
        return 0;
    }

    /**
     * Installs what has <b>nothing</b> installed, once, before this container reports itself ready.
     *
     * <h2>What it is for</h2>
     * A Minecraft server in this deployment refuses to start on an empty {@code plugins} folder,
     * and filling it was {@code docker compose run --rm updater apply} - a command typed by a person
     * with a shell on the host. Arcane deploys by pulling images and has no way to type it, so
     * without this a stack managed from Arcane could never reach a running state on its own. This is
     * the whole of "deployable from environment variables alone".
     *
     * <h2>It cannot move a version</h2>
     * {@link UpdatePlan#onlyMissing()} drops everything but {@code MISSING}, so an artefact that
     * already has a jar keeps it however old it is. A container that comes back up after a crash
     * therefore finds nothing missing and does nothing at all - this module's first rule, kept as a
     * property of the plan rather than a promise in a comment. Upgrades stay a request somebody
     * makes.
     *
     * <h2>A failure here does not stop the readiness marker, deliberately</h2>
     * The tempting symmetry is with the schema above, which refuses to become ready. It is the wrong
     * symmetry: {@code serve} runs on <em>every</em> restart of a live network, not only on a fresh
     * one, so a GitHub outage during an ordinary redeploy would take down four servers and the bot
     * that were about to come back up perfectly well. The failure this guards against is also
     * already reported precisely and by name one layer down - the entrypoint stops the container and
     * says which folder is empty - whereas an updater that never goes healthy says only that
     * everything is waiting for it. So this logs loudly and lets the stack come up.
     *
     * <p>It takes the same advisory lock as {@code updater apply}, so a person running one by hand
     * at the moment a redeploy lands is refused rather than interleaved.</p>
     */
    private static void bootstrap(final UpdaterSpec config, final Database database) {
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final java.sql.SQLException failure) {
            log.error("Bootstrap: could not take the updater lock, so no missing file was installed."
                    + " Any server whose plugins folder is empty will refuse to start and say so.",
                    failure);
            return;
        }
        if (lock.isEmpty()) {
            log.warn("Bootstrap: another updater run holds the lock, so this start installed nothing."
                    + " That run is doing the same work; nothing here needs repeating.");
            return;
        }

        try (RunLock held = lock.get()) {
            final UpdatePlan missing;
            try {
                missing = Runs.resolve(config).onlyMissing();
            } catch (final RuntimeException failure) {
                log.error("Bootstrap: nothing could be resolved, so no missing file was installed."
                        + " Any server whose plugins folder is empty will refuse to start and say"
                        + " so.", failure);
                return;
            }

            if (!missing.hasMissing()) {
                if (missing.hasFailures()) {
                    // NOT the normal case, and it must not be logged as one. Nothing is missing
                    // among the artefacts that could be checked, and some could not be checked at
                    // all - which is exactly the difference this module refuses to blur.
                    log.warn("Bootstrap: nothing is missing among the artefacts that could be"
                            + " checked, but {} could not be checked at all. That is not the same as"
                            + " a full set of volumes. Nothing was installed:\n{}",
                            missing.withStatus(Change.Status.UNRESOLVED).size(),
                            Report.render(missing));
                } else {
                    log.info("Bootstrap: every volume already holds a jar for everything that"
                            + " belongs in it, so nothing was installed. This is the normal case on"
                            + " a restart.");
                }
                return;
            }

            log.info("Bootstrap: {} artefact(s) have nothing installed at all. Installing those, and"
                            + " only those, before this container reports ready.",
                    missing.withStatus(Change.Status.MISSING).size());
            final ApplyResult result;
            try {
                result = Runs.apply(config, missing);
            } catch (final RuntimeException failure) {
                log.error("Bootstrap: the install failed part way through. Some volumes may still be"
                        + " empty, and a server whose plugins folder is one of them will refuse to"
                        + " start and say so.", failure);
                return;
            }

            // The report goes through the logger here, not stdout: this is a container's start-up
            // record rather than a command's output, and the two are read in different places.
            if (result.hasFailures()) {
                log.error("Bootstrap finished with failures:\n{}", Report.render(result));
            } else if (result.skippedAnything()) {
                // A service whose season jar could not be resolved is skipped WHOLE - Applier's
                // all-or-nothing rule - so this is the outage case, and its servers will refuse to
                // start on the empty folders it leaves. Loud, because the alternative is the line
                // that started all this: "Everything asked for was done."
                log.warn("Bootstrap could not install everything, and what it skipped it skipped"
                        + " entirely. A server whose plugins folder is still empty will refuse to"
                        + " start and say so:\n{}", Report.render(result));
            } else {
                log.info("Bootstrap finished:\n{}", Report.render(result));
            }
        }
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
