package eu.nordtal.s2.steward.worker;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.online.OnlineDirectory;
import eu.nordtal.s2.database.online.OnlineRoster;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.api.WorkerApi;
import eu.nordtal.s2.steward.worker.apply.ApplyResult;
import eu.nordtal.s2.steward.worker.backup.Backups;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.backup.Schedules;
import eu.nordtal.s2.steward.worker.backup.TarSnapshots;
import eu.nordtal.s2.steward.worker.bunq.PaymentLoop;
import eu.nordtal.s2.steward.worker.config.Configs;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.DeployerRecreate;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.metric.Sampler;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.plan.Change;
import eu.nordtal.s2.steward.worker.plan.Report;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import eu.nordtal.s2.steward.worker.run.Runs;
import eu.nordtal.s2.steward.worker.schema.RunLock;
import eu.nordtal.s2.steward.worker.schema.Schema;
import eu.nordtal.s2.steward.worker.schema.ServeLock;
import eu.nordtal.s2.steward.worker.serve.PostgresNotifications;
import eu.nordtal.s2.steward.worker.serve.Runner;
import eu.nordtal.s2.steward.worker.serve.UpdateServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Entry point: {@code report} (the default, read-only), {@code migrate}, {@code bootstrap} and {@code serve}.
 *
 * Exit code 1 means no report could be produced or a bootstrap failed; a pending update is not a failure.
 */
@Slf4j
public final class StewardWorker {

    /** The one clock of this process. */
    private static final Clock CLOCK = NetworkTime.clock();

    /** How long a settled {@code command_request} row is kept. */
    private static final int COMMAND_REQUEST_RETENTION_DAYS = 30;

    private static final String DEFAULT_CONFIG_DIR = "config";

    /**
     * Fills empty volumes on a fresh deployment, which has no schema and so cannot ask for an update.
     *
     * It installs only what is missing, so it can never replace a jar under a running server.
     */
    private static final String BOOTSTRAP = "bootstrap";

    /** The schema on its own. */
    private static final String MIGRATE = "migrate";

    private static final String SERVE = "serve";

    /**
     * The read-only run, by name.
     *
     * {@code docker compose run --rm steward-worker} passes the service's command, so the default is unreachable.
     */
    private static final String REPORT = "report";

    /**
     * Touched once the schema is current and the loop is about to start; the compose healthcheck waits for it.
     *
     * It lives in {@code /tmp} so a restart makes it false again.
     */
    private static final Path READY_MARKER = Path.of("/tmp/steward-worker-ready");

    private StewardWorker() {}

    public static void main(final String[] args) {
        final Path configDirectory =
                Path.of(System.getenv().getOrDefault("NORDTAL_STEWARD_CONFIG_DIR", DEFAULT_CONFIG_DIR));

        final int status = switch (command(args)) {
            // The schema on its own, so this works on a host with no release yet.
            case MIGRATE -> migrate(configDirectory) ? 0 : 1;
            case SERVE -> serve(configDirectory);
            case BOOTSTRAP -> bootstrap(configDirectory);
            // Retired, and named so it does not fall through to a report that looks like it worked.
            case "apply" -> {
                log.error("`apply` is retired. It installed jars underneath running servers. Use"
                        + " `bootstrap` to fill EMPTY volumes on a fresh deployment, or ask for an"
                        + " update from Discord or in game - that stops each server before its jars"
                        + " move and starts it again afterwards.");
                yield 1;
            }
            // Named as well as defaulted, since `docker compose run --rm steward-worker` cannot reach the default.
            case REPORT -> report(configDirectory);
            default -> report(configDirectory);
        };
        System.exit(status);
    }

    private static int report(final Path configDirectory) {
        final StewardSpec config = stewardConfig(configDirectory);
        if (config == null) {
            return 1;
        }
        // The one caller that may run with no database, so its absence is said out loud.
        final DatabaseSpec databaseConfig = databaseConfig(configDirectory);
        final Database opened = databaseConfig == null ? null : DatabaseWaiting.openDatabase(databaseConfig);
        try {
            final eu.nordtal.s2.steward.worker.plugin.PluginDirectory plugins = opened == null
                    ? eu.nordtal.s2.steward.worker.plugin.PluginDirectory.NONE
                    : eu.nordtal.s2.steward.worker.plugin.PluginDirectory.using(opened.dataSource());
            if (opened == null) {
                log.warn("No database, so any plugin added from the interface is missing from this"
                        + " report. Everything the topology names is in it.");
            }
            // stdout, not the logger, so the report can be pasted as is.
            System.out.println(Report.render(Runs.resolve(config, plugins)));
        } finally {
            if (opened != null) {
                opened.close();
            }
        }
        return 0;
    }

    /**
     * Resolves, migrates and installs what is missing, on the host, on demand.
     *
     * It writes no {@code update_request} row, since on a fresh deployment the table does not exist yet.
     */
    private static int bootstrap(final Path configDirectory) {
        final StewardSpec config = stewardConfig(configDirectory);
        final DatabaseSpec databaseConfig = databaseConfig(configDirectory);
        if (config == null || databaseConfig == null) {
            return 1;
        }

        final Database opened = DatabaseWaiting.openDatabase(databaseConfig);
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            final Optional<RunLock> lock;
            try {
                lock = RunLock.tryAcquire(database.dataSource());
            } catch (final java.sql.SQLException failure) {
                log.error(
                        "Could not reach the database to take the steward-worker lock. Nothing" + " was done.",
                        failure);
                return 1;
            }
            if (lock.isEmpty()) {
                log.error("Another steward-worker run is in progress - almost certainly the"
                        + " `steward-worker` service, working on a request from Discord or from in"
                        + " game. Nothing was done. Wait for it to finish and run this again.");
                return 1;
            }

            try (RunLock held = lock.get()) {
                return bootstrapUnderLock(config, database);
            }
        }
    }

    /** Resolves what is missing, prints it, migrates and installs it under the bootstrap lock. */
    private static int bootstrapUnderLock(final StewardSpec config, final Database database) {
        final UpdatePlan resolved =
                Runs.resolve(config, eu.nordtal.s2.steward.worker.plugin.PluginDirectory.using(database.dataSource()));
        final UpdatePlan plan = resolved.onlyMissing();
        System.out.println(Report.render(resolved));
        // Even when not empty, so a report naming only the install does not read as a failure.
        final int skipped = resolved.changes().stream()
                        .filter(change -> change.status().isWork())
                        .toList()
                        .size()
                - plan.changes().stream()
                        .filter(change -> change.status().isWork())
                        .toList()
                        .size();
        if (skipped > 0) {
            System.out.println("\n" + skipped + " of the entries above "
                    + (skipped == 1 ? "is an upgrade" : "are upgrades") + " rather than something missing,"
                    + " and this command does not perform upgrades - only what is absent is"
                    + " installed below. Ask for an update from Discord or in game: it"
                    + " stops each server before its jars move, which is the whole"
                    + " difference.");
        }

        // Before a single jar moves, so a plugin never meets a schema older than itself.
        try {
            Schema.migrate(database);
        } catch (final RuntimeException failure) {
            log.error("The database schema could not be applied. Nothing else was done.", failure);
            return 1;
        }

        final ApplyResult result = Runs.apply(config, plan);
        System.out.println(Report.render(result));
        return result.hasFailures() ? 1 : 0;
    }

    private static int serve(final Path configDirectory) {
        // The handle, so saving this file in Steward re-arms the two clocks.
        final ConfigHandle<StewardSpec> handle = stewardHandle(configDirectory);
        final DatabaseSpec databaseConfig = databaseConfig(configDirectory);
        if (handle == null || databaseConfig == null) {
            return 1;
        }
        final StewardSpec config = handle.get();

        final Database opened = DatabaseWaiting.openDatabase(databaseConfig);
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            return serveWithDatabase(handle, config, databaseConfig, database);
        }
    }

    /** Takes the serve lock and, once held, runs the container's whole lifetime. */
    private static int serveWithDatabase(
            final ConfigHandle<StewardSpec> handle,
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database) {
        // First: settleOrphans closes every RUNNING row, which is only right while one worker claims them.
        final Optional<ServeLock> serveLock;
        try {
            serveLock = ServeLock.acquire(database.dataSource());
        } catch (final java.sql.SQLException failure) {
            log.error(
                    "Could not reach the database to take the serve lock, so this container"
                            + " will not become ready.",
                    failure);
            return 1;
        }
        if (serveLock.isEmpty()) {
            log.error("Another steward-worker is already serving this database, and has been"
                    + " for longer than a redeploy takes to hand over. Refusing to start a"
                    + " second one: two serve loops settle each other's in-flight requests as"
                    + " failures and lose the report of whichever was actually working. If you"
                    + " meant the read-only report, that is `steward-worker report`.");
            return 1;
        }

        try (ServeLock held = serveLock.get()) {
            try {
                Schema.migrate(database);
            } catch (final RuntimeException failure) {
                // A server must not start against an unknown schema.
                log.error(
                        "The database schema could not be applied, so this container will not"
                                + " become ready. Nothing else in the stack starts until it does.",
                        failure);
                return 1;
            }
            clearOldCommandRequests(database);

            // Fill empty volumes before the marker; a failure here does not stop it.
            if (config.bootstrap()) {
                bootstrap(config, database);
            }
            markReady();

            return serveNetwork(handle, config, databaseConfig, database);
        }
    }

    /**
     * Deletes settled {@code command_request} rows older than {@link #COMMAND_REQUEST_RETENTION_DAYS}.
     *
     * Once at startup, not on a timer, since {@code serve} is not a scheduler.
     */
    private static void clearOldCommandRequests(final Database database) {
        try (CommandRequests requests = CommandRequests.borrowing(database.dataSource())) {
            final int gone = requests.deleteSettledOlderThan(COMMAND_REQUEST_RETENTION_DAYS);
            if (gone > 0) {
                log.info(
                        "Removed {} settled command requests older than {} days.",
                        gone,
                        COMMAND_REQUEST_RETENTION_DAYS);
            }
        } catch (final RuntimeException failure) {
            // Not fatal: every other service waits for this container.
            log.warn("Could not clear out old command requests; they stay where they are.", failure);
        }
    }

    private static int serveNetwork(
            final ConfigHandle<StewardSpec> handle,
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database) {
        // The start page curves, started after the marker so a missing socket never delays the servers.
        final Docker docker =
                new Docker(new DockerSocket(Path.of(config.docker().socket()), Duration.ofSeconds(30)));
        final DockerOps dockerOps = new DockerOps(docker, config.docker().project());
        final ContainerOps containers = buildContainerOps(config, dockerOps);
        if (!docker.isReachable()) {
            // Said once: without the socket an update, a restart or a backup refuses at its first step.
            log.warn(
                    "No docker socket at {}, so nothing here can stop or start a"
                            + " container: an update, a restart or a backup refuses before it"
                            + " touches anything, and there is no image drift check and no start"
                            + " page curve. Everything else works.",
                    config.docker().socket());
        }
        try (Sampler sampler = new Sampler(
                docker,
                new HostMetrics(),
                MetricDirectory.using(database.dataSource()),
                config.docker().project(),
                CLOCK)) {
            if (!config.docker().metrics()) {
                log.info("Metric sampling is off in steward.yml, so the start page will have no curves.");
            } else if (docker.isReachable()) {
                sampler.start();
            }
            return serveWithSampler(handle, config, databaseConfig, database, docker, dockerOps, containers);
        }
    }

    /**
     * The container ops an update, restart or backup stops and starts services through.
     *
     * Recreating needs the steward-deployer token; without one, {@link DockerOps} refuses by name.
     */
    private static ContainerOps buildContainerOps(final StewardSpec config, final DockerOps dockerOps) {
        if (config.deployer().token().isBlank()) {
            log.warn("deployer.token is empty in steward.yml, so this container cannot ask"
                    + " steward-deployer to recreate a service: an update whose image has"
                    + " moved stops the old container and starts it again on that same"
                    + " image, and the line stays FAILED. The setup script writes that"
                    + " secret.");
            return dockerOps;
        }
        return new DeployerRecreate(
                dockerOps,
                config.deployer().url(),
                config.deployer().token(),
                Duration.ofSeconds(config.httpTimeoutSeconds()),
                Duration.ofSeconds(config.deployer().timeoutSeconds()),
                Waiting.on(CLOCK));
    }

    private static int serveWithSampler(
            final ConfigHandle<StewardSpec> handle,
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database,
            final Docker docker,
            final DockerOps dockerOps,
            final ContainerOps containers) {
        final Backups backups = buildBackups(config, docker);

        // Shared between the server that settles rows and the runner that commits their countdown.
        final UpdateDirectory updates = UpdateDirectory.using(database.dataSource());
        // Admin-added plugins, shared by the runner's resolve and the API.
        final eu.nordtal.s2.steward.worker.plugin.PluginDirectory addedPlugins =
                eu.nordtal.s2.steward.worker.plugin.PluginDirectory.using(database.dataSource());
        // Read-only, for the actions feed.
        final AuditDirectory audit = AuditDirectory.using(database.dataSource());

        // What steward-ui reads this container through, started after the marker so its failure cannot spread.
        try (Schedules schedules = new Schedules(updates, config, CLOCK);
                WorkerApi api = buildApi(
                        config, docker, dockerOps, database, updates, audit, addedPlugins, handle, schedules)) {
            if (config.api().token().isBlank()) {
                log.warn("api.token is empty, so the internal API is not listening and"
                        + " steward-ui cannot read this container. Updates and backups are"
                        + " unaffected - they go through the database.");
            } else if (!docker.isReachable()) {
                log.warn("No docker socket, so the internal API would answer every question"
                        + " with `could not look`. It is not listening.");
            } else {
                api.start(config.api().port());
            }

            // The nightly backup and the optional scheduled update.
            schedules.arm();

            return serveWithApi(config, databaseConfig, database, containers, backups, updates, addedPlugins);
        }
    }

    /** What a BACKUP run saves with: volumes tarred from read-only mounts, the database dumped in place. */
    private static Backups buildBackups(final StewardSpec config, final Docker docker) {
        final String databaseService = config.backup().databaseService();
        return new Backups(
                new TarSnapshots(
                        Path.of(config.backup().sourcesRoot()),
                        Path.of(config.backup().outputRoot()),
                        CLOCK,
                        Duration.ofMinutes(Math.max(1, config.backup().patienceMinutes()))),
                databaseService == null || databaseService.isBlank()
                        ? null
                        : new DatabaseDump(
                                docker,
                                config.docker().project(),
                                databaseService,
                                config.backup().outputRoot(),
                                CLOCK));
    }

    /** Builds the internal API steward-ui reads through. */
    private static WorkerApi buildApi(
            final StewardSpec config,
            final Docker docker,
            final DockerOps dockerOps,
            final Database database,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final eu.nordtal.s2.steward.worker.plugin.PluginDirectory addedPlugins,
            final ConfigHandle<StewardSpec> handle,
            final Schedules schedules) {
        return new WorkerApi(
                docker,
                dockerOps,
                new Console(docker, config.docker().project()),
                new HostMetrics(),
                config.docker().project(),
                Path.of(config.backup().outputRoot()),
                config.api().token(),
                Path.of(config.api().configsRoot()),
                Path.of(config.volumesRoot()),
                updates,
                audit,
                () -> new WorkerApi.Nightly(
                        config.backup().at(),
                        config.backup().days(),
                        config.update().at(),
                        config.update().days(),
                        NetworkTime.ZONE),
                // The player counts proxy writes, and the player list next to them.
                new eu.nordtal.s2.steward.worker.api.ServicesApi(
                        OnlineDirectory.using(database.dataSource(), CLOCK),
                        OnlineRoster.using(database.dataSource(), CLOCK),
                        CLOCK),
                // The same resolve a run starts with, so the page can ask what is newest without a run.
                () -> Runs.resolve(config, addedPlugins),
                // Its own Modrinth client, living as long as the API.
                new eu.nordtal.s2.steward.worker.api.PluginsApi(
                        addedPlugins,
                        new eu.nordtal.s2.steward.worker.source.Modrinth(new eu.nordtal.s2.steward.worker.http.JdkHttp(
                                Duration.ofSeconds(config.httpTimeoutSeconds()), config.githubToken())),
                        Path.of(config.volumesRoot()),
                        eu.nordtal.s2.common.Platform.MINECRAFT,
                        java.util.Map.of(
                                eu.nordtal.s2.steward.worker.plan.Topology.PACKETEVENTS, config.packetEventsProject(),
                                eu.nordtal.s2.steward.worker.plan.Topology.VOICE_CHAT, config.voiceChatProject(),
                                eu.nordtal.s2.steward.worker.plan.Topology.VOICE_CHAT_PROXY, config.voiceChatProject(),
                                eu.nordtal.s2.steward.worker.plan.Topology.CORE_PROTECT, config.coreProtectProject()),
                        CLOCK),
                // The bot's inbox: saving a message asks it to re-read the file.
                eu.nordtal.s2.database.access.AccessRequests.on(database.dataSource()),
                // A save of this worker's own steward.yml re-arms the clocks.
                () -> {
                    try {
                        handle.reload();
                    } catch (final ConfigException broken) {
                        throw new IllegalStateException(broken.getMessage(), broken);
                    }
                    schedules.arm();
                },
                CLOCK);
    }

    /** The request loop, and the only container that calls bunq and stops and starts services. */
    private static int serveWithApi(
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory updates,
            final eu.nordtal.s2.steward.worker.plugin.PluginDirectory addedPlugins) {
        // The only evidence bunq works, since both variables are optional.
        try (PaymentLoop paymentLoop = PaymentsStartup.start(config, databaseConfig, database, CLOCK)) {
            try (UpdateServer server = new UpdateServer(
                    updates,
                    new Runner(config, database, containers, backups, updates, Waiting.on(CLOCK), addedPlugins),
                    PostgresNotifications.connector(databaseConfig),
                    Duration.ofSeconds(config.pollIntervalSeconds()),
                    CLOCK)) {
                // SIGTERM is how a redeploy asks; without this the container is killed after the grace period.
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "steward-worker-shutdown"));
                server.serve();
            }
        }
        return 0;
    }

    /**
     * Installs what has nothing installed, once, before this container reports itself ready.
     *
     * A failure only logs, since serve runs on every restart and an outage must not keep the stack down.
     */
    private static void bootstrap(final StewardSpec config, final Database database) {
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final java.sql.SQLException failure) {
            log.error(
                    "Bootstrap: could not take the steward-worker lock, so no missing file was"
                            + " installed. Any server whose plugins folder is empty will refuse to start"
                            + " and say so.",
                    failure);
            return;
        }
        if (lock.isEmpty()) {
            log.warn("Bootstrap: another steward-worker run holds the lock, so this start installed"
                    + " nothing. That run is doing the same work; nothing here needs repeating.");
            return;
        }

        try (RunLock held = lock.get()) {
            bootstrapAtStartupUnderLock(config, database);
        }
    }

    /** Resolves what is missing and installs it under the bootstrap lock. */
    private static void bootstrapAtStartupUnderLock(final StewardSpec config, final Database database) {
        final UpdatePlan missing;
        try {
            missing = Runs.resolve(
                            config, eu.nordtal.s2.steward.worker.plugin.PluginDirectory.using(database.dataSource()))
                    .onlyMissing();
        } catch (final RuntimeException failure) {
            log.error(
                    "Bootstrap: nothing could be resolved, so no missing file was installed."
                            + " Any server whose plugins folder is empty will refuse to start and say"
                            + " so.",
                    failure);
            return;
        }

        if (!missing.hasMissing()) {
            if (missing.hasFailures()) {
                // Nothing checkable is missing, but some artefacts could not be checked at all.
                log.warn(
                        "Bootstrap: nothing is missing among the artefacts that could be"
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

        installMissing(config, missing);
    }

    /** Installs what {@code missing} names, and logs how it went. */
    private static void installMissing(final StewardSpec config, final UpdatePlan missing) {
        log.info(
                "Bootstrap: {} artefact(s) have nothing installed at all. Installing those, and"
                        + " only those, before this container reports ready.",
                missing.withStatus(Change.Status.MISSING).size());
        final ApplyResult result;
        try {
            result = Runs.apply(config, missing);
        } catch (final RuntimeException failure) {
            log.error(
                    "Bootstrap: the install failed part way through. Some volumes may still be"
                            + " empty, and a server whose plugins folder is one of them will refuse to"
                            + " start and say so.",
                    failure);
            return;
        }

        // The logger, not stdout: this is the container's start-up record.
        if (result.hasFailures()) {
            log.error("Bootstrap finished with failures:\n{}", Report.render(result));
        } else if (result.skippedAnything()) {
            // A whole service is skipped when its jar could not be resolved.
            log.warn(
                    "Bootstrap could not install everything, and what it skipped it skipped"
                            + " entirely. A server whose plugins folder is still empty will refuse to"
                            + " start and say so:\n{}",
                    Report.render(result));
        } else {
            log.info("Bootstrap finished:\n{}", Report.render(result));
        }
    }

    private static void markReady() {
        try {
            Files.writeString(READY_MARKER, "ready\n");
        } catch (final IOException failure) {
            // Fatal to everything waiting on this process, so it is loud.
            log.error(
                    "Could not write the readiness marker {}. The rest of the stack will not"
                            + " start, because its healthcheck is a test for this file.",
                    READY_MARKER,
                    failure);
        }
    }

    /**
     * The subcommand, or the empty string.
     *
     * Anything unrecognised reads as the default, which cannot break anything.
     */
    private static String command(final String[] args) {
        return args.length == 0 ? "" : args[0].strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable StewardSpec stewardConfig(final Path configDirectory) {
        final ConfigHandle<StewardSpec> handle = stewardHandle(configDirectory);
        return handle == null ? null : handle.get();
    }

    private static @Nullable ConfigHandle<StewardSpec> stewardHandle(final Path configDirectory) {
        try {
            return Configs.steward(configDirectory, LoggerFactory.getLogger(Configs.class));
        } catch (final ConfigException broken) {
            // No stack trace, so the sentence is not missed.
            log.error("Refusing to run on a config that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    private static @Nullable DatabaseSpec databaseConfig(final Path configDirectory) {
        try {
            return Configs.database(configDirectory, LoggerFactory.getLogger(Configs.class))
                    .get();
        } catch (final ConfigException broken) {
            log.error("Refusing to touch the database on a config that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    /** Applies the schema on its own; {@code false} means the run must not continue. */
    private static boolean migrate(final Path configDirectory) {
        final DatabaseSpec database = databaseConfig(configDirectory);
        if (database == null) {
            return false;
        }
        // The pool first, so "not up yet" does not look like a failed migration.
        final Database opened = DatabaseWaiting.openDatabase(database);
        if (opened == null) {
            return false;
        }
        try (Database pool = opened) {
            Schema.migrate(pool);
            return true;
        } catch (final RuntimeException failed) {
            // Flyway's message names the file and the statement.
            log.error("The database schema could not be applied. Nothing else was done.", failed);
            return false;
        }
    }
}
