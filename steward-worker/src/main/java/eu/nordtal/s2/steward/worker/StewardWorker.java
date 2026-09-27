package eu.nordtal.s2.steward.worker;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.metric.MetricDirectory;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlineRoster;
import eu.nordtal.s2.common.update.UpdateDirectory;
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
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Entry point.
 *
 * Four commands: steward-worker report resolve, compare, print, exit. Touches nothing a server reads. steward-worker
 * migrate apply the database schema, and nothing else. steward-worker bootstrap migrate, then fill EMPTY volumes.
 * Upgrades nothing - see BOOTSTRAP. steward-worker serve migrate, then wait for requests from Discord and from in
 * game.
 *
 * The default is the read-only one, deliberately: a container started by accident, or with an argument that was
 * misspelled, must do the harmless thing. Everything that writes has to be asked for by name.
 *
 * {@code report} is also named, and that is not tidiness. The default is unreachable from the one command five
 * documents told an operator to type: {@code docker compose run --rm steward-worker} inherits the service's
 * {@code command} - {@code serve} - so the "prints what is installed and changes nothing" run would in fact start a
 * second long-running daemon that migrates, bootstraps and listens, hanging the terminal. It is the same when the
 * service carries no {@code command} at all: {@code run} then takes the image's {@code CMD}.
 *
 * {@code migrate} exists on its own because the schema is the one thing a deployment needs before anything else can
 * start - this container is the bootstrap, not a tool used on a running one. {@code bootstrap} does it too, before
 * it moves a single jar, so a plugin never comes up against a schema older than itself.
 *
 * {@code serve} is the container that runs all the time, and it is not a scheduler: At startup it applies the schema
 * and installs what is missing, and then does nothing at all until somebody writes a row into
 * {@code update_request}. There is no timer, no watch and no "check for updates on boot": the first rule of this
 * module is that a crash restart at three in the morning does not move a version, and a container that comes back up
 * comes back on exactly the jars it was running.
 *
 * Neither startup step breaks that rule, and it is worth being exact about why. The schema applied is whatever this
 * jar carries, and this jar is what it was. The install is restricted to artefacts with nothing installed at all (
 * {@link UpdatePlan#onlyMissing()}), so a volume that already holds a jar keeps it however old it is - a restart of
 * a live network finds nothing missing and moves nothing. What the install is for is the other case: a brand new
 * stack, where every volume is empty and a Minecraft server refuses to start without plugins. That used to need
 * {@code updater apply} typed on the host by a person with a shell on it.
 *
 * Both are done here because steward-worker is the only process that migrates and the whole stack starts at once
 * after a redeploy; {@code compose.yml} makes every other service wait for the readiness marker this writes once the
 * schema is current and the empty volumes are filled.
 *
 * Exit codes: {@code 0} when a report was produced, whatever the report says - including one full of rows that could
 * not be checked, because that is the answer and it is in the text. {@code 1} when no report could be produced at
 * all, which in practice means a config this module refuses, and when a {@code bootstrap} run had a failure in it -
 * there the non-zero is earned: something was attempted and did not work.
 *
 * Deliberately not "non-zero when an update is available": that would make every scheduler treat a pending update as
 * a failure, and this module's first rule is that nothing updates on a schedule.
 */
@Slf4j
public final class StewardWorker {

    /**
     * How long a settled {@code command_request} row is kept.
     *
     * Thirty days: long enough to look back at an incident from the last few weeks, short enough that "a message in
     * flight" is an honest description of the row. Not configurable, because a
     * retention window nobody has decided is one every deployment answers differently.
     */
    private static final int COMMAND_REQUEST_RETENTION_DAYS = 30;

    /** Mirrors the bot's layout: WORKDIR /app, config in a volume at /app/config. */
    private static final String DEFAULT_CONFIG_DIR = "config";

    /**
     * The bootstrap: fill empty volumes on a deployment that has none.
     *
     * The name matters: an earlier command resolved and installed everything unconditionally, on a host where the
     * servers were very likely running, replacing jars underneath them. This one does the one job that genuinely
     * cannot be done any other way: a fresh deployment has no schema, so it has no {@code update_request} table, so it
     * cannot ask for an update at all.
     *
     * It installs only what is missing ( {@link UpdatePlan#onlyMissing()}), the same rule {@code serve} follows when it
     * fills empty volumes at startup. That is what makes it structurally incapable of replacing a jar underneath a
     * running server: there is nothing to replace, only gaps to fill. Upgrading is what {@code UPDATE} is for, and that
     * stops the servers first.
     */
    private static final String BOOTSTRAP = "bootstrap";

    /** The schema on its own - the first thing a deployment needs and the last thing to move. */
    private static final String MIGRATE = "migrate";

    /** The long-running mode: the schema, then the request loop. */
    private static final String SERVE = "serve";

    /**
     * The read-only run, by name.
     *
     * Why it needs a name when it is already the default: because {@code docker compose run --rm steward-worker} does
     * not reach the default. Compose passes the service's own {@code command} to a {@code run} that names none - and
     * when the service defines none it falls through to the image's {@code CMD} instead, true of both. A bare command
     * documented as the harmless report would instead start a second {@code serve} daemon that migrates, bootstraps
     * and listens, while the operator watches a terminal that never comes back.
     *
     * Anchoring {@code serve} in the image rather than in the service does not help, for exactly the same reason. The
     * only thing that makes a typed command do what it says is a name for what it does.
     */
    private static final String REPORT = "report";

    /**
     * Touched once the schema is current and the loop is about to start.
     *
     * {@code compose.yml} 's healthcheck is {@code test -f} on this path, and every other service waits for it. A file
     * rather than a port because this process does not serve one, and in {@code /tmp} rather than a volume because it
     * must be false again after a restart - a readiness marker that survives the process it describes is worse than
     * none.
     */
    private static final Path READY_MARKER = Path.of("/tmp/steward-worker-ready");

    private StewardWorker() {}

    public static void main(final String[] args) {
        final Path configDirectory =
                Path.of(System.getenv().getOrDefault("NORDTAL_STEWARD_CONFIG_DIR", DEFAULT_CONFIG_DIR));

        final int status = switch (command(args)) {
            // The schema, on its own: nothing else is read, so this works against a host with no release yet.
            case MIGRATE -> migrate(configDirectory) ? 0 : 1;
            case SERVE -> serve(configDirectory);
            case BOOTSTRAP -> bootstrap(configDirectory);
            // Retired, and named rather than falling through to a report: a silent report would look like it worked.
            case "apply" -> {
                log.error("`apply` is retired. It installed jars underneath running servers. Use"
                        + " `bootstrap` to fill EMPTY volumes on a fresh deployment, or ask for an"
                        + " update from Discord or in game - that stops each server before its jars"
                        + " move and starts it again afterwards.");
                yield 1;
            }
            // Named as well as defaulted: `docker compose run --rm steward-worker` cannot reach the default.
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
        // The one caller that may run with no database - a fresh host has none yet - so its absence is said out loud.
        final DatabaseSpec databaseConfig = databaseConfig(configDirectory);
        final Database opened = databaseConfig == null ? null : DatabaseWaiting.openDatabase(databaseConfig);
        try {
            final eu.nordtal.s2.common.plugin.PluginDirectory plugins = opened == null
                    ? eu.nordtal.s2.common.plugin.PluginDirectory.NONE
                    : eu.nordtal.s2.common.plugin.PluginDirectory.using(opened.dataSource());
            if (opened == null) {
                log.warn("No database, so any plugin added from the interface is missing from this"
                        + " report. Everything the topology names is in it.");
            }
            // stdout, not the logger: a report wrapped in timestamps and thread names is one nobody pastes anywhere.
            System.out.println(Report.render(Runs.resolve(config, plugins)));
        } finally {
            if (opened != null) {
                opened.close();
            }
        }
        return 0;
    }

    /**
     * Resolve, migrate, install - on the host, on demand.
     *
     * This is the bootstrap command, and it does not write a row into {@code update_request}: on a fresh deployment the
     * table does not exist until the migration this run performs, so a request row would have to be written half way
     * through its own run. The daemon's requests are recorded; this one is recorded in whoever's shell history it was
     * typed into.
     *
     * Only what is missing: a command that resolved and installed everything unconditionally would happily replace
     * a jar under a running server on a live deployment. Installing into gaps only means the worst it can do there
     * is nothing.
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

    /** Resolves what is missing, prints it, migrates and installs it - the body of {@link #bootstrap(Path)}'s lock. */
    private static int bootstrapUnderLock(final StewardSpec config, final Database database) {
        final UpdatePlan resolved =
                Runs.resolve(config, eu.nordtal.s2.common.plugin.PluginDirectory.using(database.dataSource()));
        final UpdatePlan plan = resolved.onlyMissing();
        System.out.println(Report.render(resolved));
        // Even when not empty: upgrades printed above, then a report naming only the install, reads as a failure.
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

        // Before a single jar moves: a plugin must never come up against a schema older than itself.
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
        // The handle, not only its value: saving this file in Steward re-reads it and re-arms the two clocks.
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

    /** Takes the serve lock and, once held, runs the container's whole lifetime - the body of {@link #serve}. */
    private static int serveWithDatabase(
            final ConfigHandle<StewardSpec> handle,
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database) {
        // BEFORE ANYTHING ELSE: settleOrphans closes every RUNNING row, true only when one worker claims them.
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
                // Refusing to come up is right: a server that started against an unknown schema simply must not.
                log.error(
                        "The database schema could not be applied, so this container will not"
                                + " become ready. Nothing else in the stack starts until it does.",
                        failure);
                return 1;
            }
            clearOldCommandRequests(database);

            // Fill empty volumes before the marker below - a failure here does NOT stop it, see #bootstrap below.
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
     * Once, here, next to settleOrphans and for the same reason: bounded housekeeping that is safe precisely
     * because nothing else has started yet. Not on a timer - this module's first rule is that {@code serve} is
     * not a scheduler - so a container that has not restarted in a month keeps a month and a day of rows.
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
            // Not fatal: this container is what every other service waits for, and rows staying beats an outage.
            log.warn("Could not clear out old command requests; they stay where they are.", failure);
        }
    }

    /** Opens the Docker daemon and the container ops a run stops and starts services through, once ready is marked. */
    private static int serveNetwork(
            final ConfigHandle<StewardSpec> handle,
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database) {
        // The curves on the start page. Started AFTER the marker, so a missing socket never delays the four servers.
        final Docker docker =
                new Docker(new DockerSocket(Path.of(config.docker().socket()), Duration.ofSeconds(30)));
        // One instance, shared: a second one would be a second answer to the same question about the same daemon.
        final DockerOps dockerOps = new DockerOps(docker, config.docker().project());
        final ContainerOps containers = buildContainerOps(config, dockerOps);
        if (!docker.isReachable()) {
            // Said once, here: without the socket an update, a restart or a backup refuses at its first step.
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
                config.docker().project())) {
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
     * The one thing {@link DockerOps} refuses: recreating a container needs the compose file, and only
     * steward-deployer has it. An empty token leaves this exactly as it always was - DockerOps' own refusal,
     * named - because a container that cannot authenticate to the deployer must not silently pretend it can.
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
                Duration.ofSeconds(config.deployer().timeoutSeconds()));
    }

    /** What a BACKUP run saves the volumes and the database with, once the sampler is running. */
    private static int serveWithSampler(
            final ConfigHandle<StewardSpec> handle,
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database,
            final Docker docker,
            final DockerOps dockerOps,
            final ContainerOps containers) {
        final Backups backups = buildBackups(config, docker);

        // One directory, shared between the server that settles rows and the runner that commits their countdown.
        final UpdateDirectory updates = UpdateDirectory.using(database.dataSource());
        // Admin-added plugins. One directory for the three readers: the runner's resolve and the API's two uses.
        final eu.nordtal.s2.common.plugin.PluginDirectory addedPlugins =
                eu.nordtal.s2.common.plugin.PluginDirectory.using(database.dataSource());
        // Read-only, for ActionsApi's feed - the run loop never touches audit_log, only the API does.
        final AuditDirectory audit = AuditDirectory.using(database.dataSource());

        // What steward-ui reads this container through. Started after the marker: a failed web layer must not spread.
        try (Schedules schedules = new Schedules(updates, config, ZoneId.systemDefault());
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

            // The nightly backup, asked for here rather than by `smp`, and the optional scheduled update beside it.
            schedules.arm();

            return serveWithApi(config, databaseConfig, database, containers, backups, updates, addedPlugins);
        }
    }

    /** What a BACKUP run saves with: volumes tarred from their read-only mounts, the database pg_dump'd in place. */
    private static Backups buildBackups(final StewardSpec config, final Docker docker) {
        final String databaseService = config.backup().databaseService();
        return new Backups(
                new TarSnapshots(
                        Path.of(config.backup().sourcesRoot()),
                        Path.of(config.backup().outputRoot()),
                        Clock.systemUTC(),
                        Duration.ofMinutes(Math.max(1, config.backup().patienceMinutes()))),
                databaseService == null || databaseService.isBlank()
                        ? null
                        : new DatabaseDump(
                                docker,
                                config.docker().project(),
                                databaseService,
                                config.backup().outputRoot(),
                                Clock.systemUTC()));
    }

    /** Builds the internal API steward-ui reads through - see {@code WorkerApi} for what each part is. */
    private static WorkerApi buildApi(
            final StewardSpec config,
            final Docker docker,
            final DockerOps dockerOps,
            final Database database,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final eu.nordtal.s2.common.plugin.PluginDirectory addedPlugins,
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
                        ZoneId.systemDefault()),
                // The player counts proxy writes, and the player list next to them - same pool again.
                new eu.nordtal.s2.steward.worker.api.ServicesApi(
                        OnlineDirectory.using(database.dataSource()), OnlineRoster.using(database.dataSource())),
                // The same resolve a run starts with, as a supplier: the page asks "what is newest" without a run.
                () -> Runs.resolve(config, addedPlugins),
                // Its own Modrinth: the resolver builds one per run, but this one lives as long as the API does.
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
                                eu.nordtal.s2.steward.worker.plan.Topology.CORE_PROTECT, config.coreProtectProject())),
                // The bot's inbox: saving a message asks it to re-read the file instead of waiting for a restart.
                eu.nordtal.s2.common.access.AccessRequests.on(database.dataSource()),
                // A save of this worker's own steward.yml: re-arm the clocks, so a schedule change needs no restart.
                () -> {
                    try {
                        handle.reload();
                    } catch (final ConfigException broken) {
                        throw new IllegalStateException(broken.getMessage(), broken);
                    }
                    schedules.arm();
                });
    }

    /** THE BANK and the request loop: the only container that calls bunq, stops and starts services. */
    private static int serveWithApi(
            final StewardSpec config,
            final DatabaseSpec databaseConfig,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory updates,
            final eu.nordtal.s2.common.plugin.PluginDirectory addedPlugins) {
        // The whole evidence bunq works: both variables are optional, so a misnamed one silently never notices.
        try (PaymentLoop paymentLoop = PaymentsStartup.start(config, databaseConfig, database)) {
            try (UpdateServer server = new UpdateServer(
                    updates,
                    new Runner(config, database, containers, backups, updates, addedPlugins),
                    PostgresNotifications.connector(databaseConfig),
                    Duration.ofSeconds(config.pollIntervalSeconds()),
                    Clock.systemUTC())) {
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
     * What it is for: A Minecraft server in this deployment refuses to start on an empty {@code plugins} folder, and
     * filling it was {@code docker compose run --rm updater apply} - a command typed by a person with a shell on the
     * host. A deployment that only pulls images cannot type it, so without this a fresh stack could never reach a
     * running state on its own. This is the whole of "deployable from environment variables alone".
     *
     * It cannot move a version: {@link UpdatePlan#onlyMissing()} drops everything but {@code MISSING}, so an artefact
     * that already has a jar keeps it however old it is. A container that comes back up after a crash therefore finds
     * nothing missing and does nothing at all - this module's first rule, kept as a property of the plan rather than a
     * promise in a comment. Upgrades stay a request somebody makes.
     *
     * A failure here does not stop the readiness marker, deliberately: The tempting symmetry is with the schema above,
     * which refuses to become ready. It is the wrong symmetry: {@code serve} runs on every restart of a live network,
     * not only on a fresh one, so a GitHub outage during an ordinary redeploy would take down four servers and the bot
     * that were about to come back up perfectly well. The failure this guards against is already reported precisely
     * and by name one layer down - the entrypoint stops the container and says which folder is empty - whereas a worker
     * that never goes healthy says only that everything is waiting for it. So this logs loudly and lets the stack come
     * up.
     *
     * It takes the same advisory lock as an update run, so a redeploy landing in the middle of one is refused rather
     * than interleaved.
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

    /** Resolves what is missing and installs it - the body of {@link #bootstrap(StewardSpec, Database)}'s lock. */
    private static void bootstrapAtStartupUnderLock(final StewardSpec config, final Database database) {
        final UpdatePlan missing;
        try {
            missing = Runs.resolve(config, eu.nordtal.s2.common.plugin.PluginDirectory.using(database.dataSource()))
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
                // Not the normal case: nothing checkable is missing, but some artefacts could not be checked at all.
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

    /** Installs what {@code missing} names, and logs how it went - the tail of {@link #bootstrapAtStartupUnderLock}. */
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

        // Through the logger, not stdout: this is a container's start-up record, read in a different place.
        if (result.hasFailures()) {
            log.error("Bootstrap finished with failures:\n{}", Report.render(result));
        } else if (result.skippedAnything()) {
            // A whole service is skipped when its jar could not be resolved (Applier's all-or-nothing rule).
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
            // Not fatal to this process, but fatal to everything waiting on it - so it is loud.
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
     * Anything unrecognised reads as the default rather than as an error: the default is the run that cannot break
     * anything, and refusing to start over a typo would mean a person retries - possibly with the typo fixed into
     * {@code apply}.
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
            // Named file, named setting, no stack trace: a 40-line trace above the sentence is how it gets missed.
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

    /**
     * Applies the schema on its own. {@code false} means the run must not continue.
     *
     * The two failures are told apart because they need different people: a config this module refuses is an edit to
     * {@code database.yml}, and a migration that fails is a look at the SQL Flyway names in its own message.
     */
    private static boolean migrate(final Path configDirectory) {
        final DatabaseSpec database = databaseConfig(configDirectory);
        if (database == null) {
            return false;
        }
        // The pool first, on its own: "not up yet" must not arrive looking like "a migration failed".
        final Database opened = DatabaseWaiting.openDatabase(database);
        if (opened == null) {
            return false;
        }
        try (Database pool = opened) {
            Schema.migrate(pool);
            return true;
        } catch (final RuntimeException failed) {
            // Flyway's own message names the file and the statement - printed with the cause, detail and all.
            log.error("The database schema could not be applied. Nothing else was done.", failed);
            return false;
        }
    }
}
