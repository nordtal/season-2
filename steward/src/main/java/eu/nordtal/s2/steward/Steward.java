package eu.nordtal.s2.steward;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxTable;
import eu.nordtal.s2.database.inbox.Inboxes;
import eu.nordtal.s2.database.inbox.StewardRequest;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.online.OnlineDirectory;
import eu.nordtal.s2.database.online.OnlineRoster;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.settings.DatabaseSettings;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.EnvironmentSettings;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.steward.agent.AgentRecreate;
import eu.nordtal.s2.steward.api.StackApi;
import eu.nordtal.s2.steward.apply.ApplyResult;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.backup.Backups;
import eu.nordtal.s2.steward.backup.DatabaseDump;
import eu.nordtal.s2.steward.backup.Schedules;
import eu.nordtal.s2.steward.backup.TarSnapshots;
import eu.nordtal.s2.steward.bunq.PaymentLoop;
import eu.nordtal.s2.steward.config.StewardSettings;
import eu.nordtal.s2.steward.config.StewardSpec;
import eu.nordtal.s2.steward.config.WebSpec;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.docker.Console;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerOps;
import eu.nordtal.s2.steward.docker.DockerSocket;
import eu.nordtal.s2.steward.host.HostMetrics;
import eu.nordtal.s2.steward.http.SourceHttp;
import eu.nordtal.s2.steward.metric.Sampler;
import eu.nordtal.s2.steward.ops.ContainerOps;
import eu.nordtal.s2.steward.plan.Change;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import eu.nordtal.s2.steward.run.Report;
import eu.nordtal.s2.steward.run.Runs;
import eu.nordtal.s2.steward.schema.RunLock;
import eu.nordtal.s2.steward.schema.Schema;
import eu.nordtal.s2.steward.schema.ServeLock;
import eu.nordtal.s2.steward.serve.Runner;
import eu.nordtal.s2.steward.serve.UpdateServer;
import eu.nordtal.s2.steward.web.Web;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Entry point: {@code report} (the default), {@code migrate}, {@code bootstrap}, {@code serve} and two host commands.
 *
 * Those are {@code forget-factors} and {@code generate-vapid-keys}; exit code 1 is no report or a failed bootstrap.
 */
@Slf4j
public final class Steward {

    /** The one clock of this process. */
    private static final Clock CLOCK = NetworkTime.clock();

    /** The logger the settings name what they refused or ignored in. */
    private static final org.slf4j.Logger SETTINGS_LOG = LoggerFactory.getLogger(StewardSettings.class);

    /** How long a settled request is kept in its inbox. */
    private static final java.time.Duration REQUEST_RETENTION = java.time.Duration.ofDays(30);

    /** Where the last installation's settings files are mounted, imported once and then deleted. */
    private static final String DEFAULT_CONFIG_DIR = "config";

    /**
     * Fills empty volumes on a fresh deployment, which has no schema and so cannot ask for an update.
     *
     * It installs only what is missing, so it can never replace a jar under a running server.
     */
    private static final String BOOTSTRAP = "bootstrap";

    /** The schema on its own. */
    private static final String MIGRATE = "migrate";

    /** The run loop, the payment loop, the scheduled clocks and the web interface, for the container's lifetime. */
    private static final String SERVE = "serve";

    /** Clears a signed-in admin's second factor. Reachable only from a shell on the host. */
    private static final String FORGET = "forget-factors";

    /**
     * The read-only run, by name.
     *
     * {@code docker compose run --rm steward} passes the service's command, so the default is unreachable.
     */
    private static final String REPORT = "report";

    private Steward() {}

    public static void main(final String[] args) {
        final Path configDirectory =
                Path.of(System.getenv().getOrDefault("NORDTAL_STEWARD_CONFIG_DIR", DEFAULT_CONFIG_DIR));

        final int status = switch (command(args)) {
            // The schema on its own, so this works on a host with no release yet.
            case MIGRATE -> migrate() ? 0 : 1;
            case SERVE -> serve(configDirectory);
            case BOOTSTRAP -> bootstrap(configDirectory);
            case FORGET -> ForgetFactors.run(args, databaseConfig(), CLOCK);
            case Web.GENERATE_VAPID_KEYS -> generateVapidKeys();
            // Retired, and named so it does not fall through to a report that looks like it worked.
            case "apply" -> {
                log.error("`apply` is retired. It installed jars underneath running servers. Use"
                        + " `bootstrap` to fill EMPTY volumes on a fresh deployment, or ask for an"
                        + " update from Discord or in game - that stops each server before its jars"
                        + " move and starts it again afterwards.");
                yield 1;
            }
            // Named as well as defaulted, since `docker compose run --rm steward` cannot reach the default.
            case REPORT -> report();
            default -> report();
        };
        System.exit(status);
    }

    private static int report() {
        // The one caller that may run with no database, so its absence is said out loud.
        final DatabaseSpec databaseConfig = databaseConfig();
        final Database opened =
                databaseConfig == null ? null : DatabaseWaiting.openDatabase(databaseConfig, Waiting.on(CLOCK));
        try {
            final StewardSpec config = reportConfig(opened);
            if (config == null) {
                return 1;
            }
            final eu.nordtal.s2.steward.plugin.PluginDirectory plugins = opened == null
                    ? eu.nordtal.s2.steward.plugin.PluginDirectory.NONE
                    : eu.nordtal.s2.steward.plugin.PluginDirectory.using(opened.dataSource());
            if (opened == null) {
                log.warn("No database, so any plugin added from the interface is missing from this"
                        + " report. Everything the topology names is in it.");
            }
            // stdout, not the logger, so the report can be pasted as is.
            System.out.println(Report.render(
                    Runs.resolve(config, plugins, opened == null ? null : SettingStore.using(opened.dataSource()))));
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
     * It writes no row into the run inbox, since on a fresh deployment the table does not exist yet.
     */
    private static int bootstrap(final Path configDirectory) {
        final DatabaseSpec databaseConfig = databaseConfig();
        if (databaseConfig == null) {
            return 1;
        }

        final Database opened = DatabaseWaiting.openDatabase(databaseConfig, Waiting.on(CLOCK));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            final Optional<RunLock> lock;
            try {
                lock = RunLock.tryAcquire(database.dataSource());
            } catch (final java.sql.SQLException failure) {
                log.error("Could not reach the database to take the run lock. Nothing was done.", failure);
                return 1;
            }
            if (lock.isEmpty()) {
                log.error("Another run is in progress - almost certainly the `steward` service,"
                        + " working on a request from Discord, from in game or from the web. Nothing was"
                        + " done. Wait for it to finish and run this again.");
                return 1;
            }

            try (RunLock held = lock.get()) {
                return bootstrapUnderLock(configDirectory, database);
            }
        }
    }

    /** Migrates, then resolves what is missing, prints it and installs it under the bootstrap lock. */
    private static int bootstrapUnderLock(final Path configDirectory, final Database database) {
        // Before a single jar moves, so a plugin never meets a schema older than itself; the settings live in it.
        try {
            Schema.migrate(database, Schema.passwords(System.getenv()));
        } catch (final RuntimeException failure) {
            log.error("The database schema could not be applied. Nothing else was done.", failure);
            return 1;
        }
        final DatabaseSettings settings =
                StewardSettings.importing(database.dataSource(), configDirectory, SETTINGS_LOG);
        final StewardSpec config;
        try {
            config = StewardSettings.steward(settings).get();
        } catch (final SettingsException broken) {
            log.error("Refusing to run on settings that cannot be read: {}", broken.getMessage());
            return 1;
        }
        settings.retireFiles();
        final SettingStore store = SettingStore.using(database.dataSource());
        final UpdatePlan resolved =
                Runs.resolve(config, eu.nordtal.s2.steward.plugin.PluginDirectory.using(database.dataSource()), store);
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

        final ApplyResult result = Runs.apply(config, plan, store);
        System.out.println(Report.render(result));
        return result.hasFailures() ? 1 : 0;
    }

    private static int serve(final Path configDirectory) {
        final DatabaseSpec databaseConfig = databaseConfig();
        if (databaseConfig == null) {
            return 1;
        }
        final Database opened = DatabaseWaiting.openDatabase(databaseConfig, Waiting.on(CLOCK));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            return serveWithDatabase(configDirectory, databaseConfig, database);
        }
    }

    /**
     * The settings {@code serve} runs on, taken once the schema is current.
     *
     * @param handle the steward group, so a change in Steward re-arms the two clocks
     * @param config what {@code handle} hands out, which reads through to every reload
     * @param settings where both groups came from, listened to for a change
     */
    private record Configs(
            Setting<StewardSpec> handle,
            StewardSpec config,
            WebSpec web,
            DatabaseSpec database,
            DatabaseSettings settings) {}

    /** Takes the serve lock and, once held, runs the container's whole lifetime. */
    private static int serveWithDatabase(
            final Path configDirectory, final DatabaseSpec databaseConfig, final Database database) {
        // First: settleOrphans closes every RUNNING row, which is only right while one process claims them.
        final Optional<ServeLock> serveLock;
        try {
            serveLock = ServeLock.acquire(database.dataSource(), Waiting.on(CLOCK));
        } catch (final java.sql.SQLException failure) {
            log.error(
                    "Could not reach the database to take the serve lock, so this container"
                            + " will not become ready.",
                    failure);
            return 1;
        }
        if (serveLock.isEmpty()) {
            log.error("Another steward is already serving this database, and has been"
                    + " for longer than a redeploy takes to hand over. Refusing to start a"
                    + " second one: two serve loops settle each other's in-flight requests as"
                    + " failures and lose the report of whichever was actually working. If you"
                    + " meant the read-only report, that is `steward report`.");
            return 1;
        }

        try (ServeLock held = serveLock.get()) {
            try {
                Schema.migrate(database, Schema.passwords(System.getenv()));
            } catch (final RuntimeException failure) {
                // A server must not start against an unknown schema.
                log.error(
                        "The database schema could not be applied, so this container will not"
                                + " become ready. Nothing else in the stack starts until it does.",
                        failure);
                return 1;
            }
            final Configs configs = configsOf(configDirectory, databaseConfig, database);
            if (configs == null) {
                return 1;
            }
            final StewardSpec config = configs.config();
            clearOldRequests(database);

            // Fill empty volumes before the marker; a failure here does not stop it.
            if (config.bootstrap()) {
                bootstrap(config, database);
            }
            markReady();

            return serveNetwork(configs, database);
        }
    }

    /** Takes both groups out of the database, importing the last installation's files once, or {@code null}. */
    private static @Nullable Configs configsOf(
            final Path configDirectory, final DatabaseSpec databaseConfig, final Database database) {
        final DatabaseSettings settings =
                StewardSettings.importing(database.dataSource(), configDirectory, SETTINGS_LOG);
        try {
            final Setting<StewardSpec> handle = StewardSettings.steward(settings);
            final WebSpec web = StewardSettings.web(settings).get();
            settings.retireFiles();
            return new Configs(handle, handle.get(), web, databaseConfig, settings);
        } catch (final SettingsException broken) {
            // No stack trace, so the sentence is not missed.
            log.error("Refusing to serve on settings that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    /**
     * Deletes the settled requests of every inbox but the run inbox older than {@link #REQUEST_RETENTION}.
     * The runs are kept: they are the history Steward shows. Once at startup, since {@code serve} is not a scheduler.
     */
    private static void clearOldRequests(final Database database) {
        for (final InboxTable<?> table : Inboxes.ALL) {
            if (table == StewardRequest.TABLE) {
                continue;
            }
            try {
                final int gone = Inbox.over(database.dataSource(), table).purge(REQUEST_RETENTION);
                if (gone > 0) {
                    log.info("Removed {} settled requests from {} older than {}.", gone, table, REQUEST_RETENTION);
                }
            } catch (final RuntimeException failure) {
                // Not fatal: every other service waits for this container.
                log.warn("Could not clear out old requests from {}; they stay where they are.", table, failure);
            }
        }
    }

    private static int serveNetwork(final Configs configs, final Database database) {
        final StewardSpec config = configs.config();
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
                            + " touches anything, every container page answers that the daemon"
                            + " is not answering, and there is no start page curve. Everything"
                            + " else works.",
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
            return serveWithSampler(configs, database, docker, dockerOps, containers);
        }
    }

    /**
     * The container ops an update, restart or backup stops and starts services through.
     *
     * Recreating needs the steward-agent token; without one, {@link DockerOps} refuses by name.
     */
    private static ContainerOps buildContainerOps(final StewardSpec config, final DockerOps dockerOps) {
        if (config.agent().token().isBlank()) {
            log.warn("agent.token is empty in steward.yml, so this container cannot ask"
                    + " steward-agent to recreate a service: an update whose image has"
                    + " moved stops the old container and starts it again on that same"
                    + " image, the line stays FAILED, and the web draws no recreate button."
                    + " The setup script writes that secret.");
            return dockerOps;
        }
        return new AgentRecreate(
                dockerOps, agentOf(config), Duration.ofSeconds(config.agent().timeoutSeconds()), Waiting.on(CLOCK));
    }

    /** The one client of steward-agent, for the runs and the web alike. */
    private static InternalClient agentOf(final StewardSpec config) {
        return new InternalClient(
                AgentRecreate.SERVICE,
                config.agent().url(),
                config.agent().token(),
                Duration.ofSeconds(config.httpTimeoutSeconds()));
    }

    private static int serveWithSampler(
            final Configs configs,
            final Database database,
            final Docker docker,
            final DockerOps dockerOps,
            final ContainerOps containers) {
        final StewardSpec config = configs.config();
        final Backups backups = buildBackups(config, docker);

        // One set of directories, shared by the run loop, the stack routes and the web.
        final Data data = new Data(database, CLOCK);
        // Admin-added plugins, shared by the runner's resolve and the API.
        final eu.nordtal.s2.steward.plugin.PluginDirectory addedPlugins =
                eu.nordtal.s2.steward.plugin.PluginDirectory.using(database.dataSource());

        try (Schedules schedules = new Schedules(data.updates(), config, CLOCK);
                StackApi stack = buildStack(config, docker, dockerOps, database, data, addedPlugins)) {
            // Started after the marker, so a failure of the interface cannot keep the servers down.
            final Web web = startWeb(configs, stack, data);
            try {
                // The nightly backup and the optional scheduled update.
                schedules.arm();
                return serveWithApi(
                        configs,
                        database,
                        containers,
                        backups,
                        data.updates(),
                        addedPlugins,
                        () -> reReadOwn(configs.handle(), schedules));
            } finally {
                web.stop();
            }
        }
    }

    /** Builds and starts the web interface on {@code web.yml}'s port, with the stack routes on it. */
    private static Web startWeb(final Configs configs, final StackApi stack, final Data data) {
        final WebSpec webConfig = configs.web();
        final StewardSpec config = configs.config();
        if (webConfig.webPush().publicKey().isBlank()) {
            log.warn("web-push has no VAPID keypair yet, so the traffic light cannot reach a phone's"
                    + " lock screen. Run `steward " + Web.GENERATE_VAPID_KEYS + "` and paste both"
                    + " lines it prints into web.yml's web-push section.");
        }
        final InternalClient agent = agentOf(config);
        final Web web = new Web(
                webConfig,
                new DiscordAuth(webConfig.discord(), webConfig.publicUrl()),
                stack,
                agent,
                !config.agent().token().isBlank(),
                data,
                CLOCK);
        web.start(webConfig.port());
        stack.warm();
        return web;
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

    /** Builds the stack routes the web serves: services, logs, the console, files, the host and plugins. */
    private static StackApi buildStack(
            final StewardSpec config,
            final Docker docker,
            final DockerOps dockerOps,
            final Database database,
            final Data data,
            final eu.nordtal.s2.steward.plugin.PluginDirectory addedPlugins) {
        // Every process's settings, which its signal re-reads; the proxy's pack among them.
        final SettingStore settings = SettingStore.using(database.dataSource());
        return new StackApi(
                docker,
                dockerOps,
                new Console(docker, config.docker().project()),
                new HostMetrics(),
                config.docker().project(),
                Path.of(config.backup().outputRoot()),
                Path.of(config.configsRoot()),
                Path.of(config.volumesRoot()),
                data.updates(),
                data.audit(),
                () -> new StackApi.Nightly(
                        config.backup().at(),
                        config.backup().days(),
                        config.update().at(),
                        config.update().days(),
                        NetworkTime.ZONE),
                // The player counts proxy writes, and the player list next to them.
                new eu.nordtal.s2.steward.api.ServicesApi(
                        OnlineDirectory.using(database.dataSource(), CLOCK),
                        OnlineRoster.using(database.dataSource(), CLOCK),
                        CLOCK),
                // The same resolve a run starts with, so the page can ask what is newest without a run.
                () -> Runs.resolve(config, addedPlugins, settings),
                // Its own Modrinth client, living as long as the API.
                new eu.nordtal.s2.steward.api.PluginsApi(
                        addedPlugins,
                        new eu.nordtal.s2.steward.source.Modrinth(SourceHttp.over(SourceHttp.client(
                                Duration.ofSeconds(config.httpTimeoutSeconds()),
                                config.githubToken(),
                                Waiting.on(CLOCK)))),
                        Path.of(config.volumesRoot()),
                        eu.nordtal.s2.common.Platform.MINECRAFT,
                        java.util.Map.of(
                                eu.nordtal.s2.steward.plan.Topology.PACKETEVENTS, config.packetEventsProject(),
                                eu.nordtal.s2.steward.plan.Topology.VOICE_CHAT, config.voiceChatProject(),
                                eu.nordtal.s2.steward.plan.Topology.VOICE_CHAT_PROXY, config.voiceChatProject(),
                                eu.nordtal.s2.steward.plan.Topology.CORE_PROTECT, config.coreProtectProject()),
                        CLOCK),
                // The bot's inbox: saving a message asks it to re-read the file.
                data.bot(),
                // The servers' inboxes: saving their bundles asks them to re-read them.
                new eu.nordtal.s2.steward.api.InboxReloader(
                        database.dataSource(), eu.nordtal.s2.common.time.Waiting.on(CLOCK)),
                settings,
                CLOCK);
    }

    /** Takes the steward group again after a change in Steward and re-arms the two clocks from it. */
    private static void reReadOwn(final Setting<StewardSpec> handle, final Schedules schedules) {
        try {
            handle.reload();
        } catch (final SettingsException broken) {
            // The problem is on the group, where Steward shows it; the values in use stay.
            log.warn("The steward settings were changed but refused, so the last ones stay: {}", broken.getMessage());
            return;
        }
        schedules.arm();
    }

    /** The request loop, the payment loop over steward-bunq, and the only process that stops and starts services. */
    private static int serveWithApi(
            final Configs configs,
            final Database database,
            final ContainerOps containers,
            final Backups backups,
            final UpdateDirectory updates,
            final eu.nordtal.s2.steward.plugin.PluginDirectory addedPlugins,
            final Runnable onSettings) {
        final StewardSpec config = configs.config();
        final DatabaseSpec databaseConfig = configs.database();
        try (PaymentLoop paymentLoop = PaymentsStartup.start(
                        config, database, Waiting.on(CLOCK), Duration.ofSeconds(config.httpTimeoutSeconds()));
                SignalHub signals = SignalHub.open(
                        databaseConfig.jdbcUrl(),
                        databaseConfig.username(),
                        databaseConfig.password(),
                        databaseConfig.queryTimeoutSeconds(),
                        "steward-signals",
                        log)) {
            try (UpdateServer server = new UpdateServer(
                    updates,
                    new Runner(config, database, containers, backups, updates, Waiting.on(CLOCK), addedPlugins),
                    CLOCK)) {
                server.listen(signals);
                configs.settings().listen(signals, onSettings);
                if (paymentLoop != null) {
                    paymentLoop.listen(signals);
                }
                signals.start();
                // SIGTERM is how a redeploy asks; without this the container is killed after the grace period.
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "steward-shutdown"));
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
                    "Bootstrap: could not take the run lock, so no missing file was"
                            + " installed. Any server whose plugins folder is empty will refuse to start"
                            + " and say so.",
                    failure);
            return;
        }
        if (lock.isEmpty()) {
            log.warn("Bootstrap: another run holds the lock, so this start installed"
                    + " nothing. That run is doing the same work; nothing here needs repeating.");
            return;
        }

        try (RunLock held = lock.get()) {
            bootstrapAtStartupUnderLock(config, database);
        }
    }

    /** Resolves what is missing and installs it under the bootstrap lock. */
    private static void bootstrapAtStartupUnderLock(final StewardSpec config, final Database database) {
        final SettingStore settings = SettingStore.using(database.dataSource());
        final UpdatePlan missing;
        try {
            missing = Runs.resolve(
                            config, eu.nordtal.s2.steward.plugin.PluginDirectory.using(database.dataSource()), settings)
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

        installMissing(config, missing, settings);
    }

    /** Installs what {@code missing} names, and logs how it went. */
    private static void installMissing(
            final StewardSpec config, final UpdatePlan missing, final SettingStore settings) {
        log.info(
                "Bootstrap: {} artefact(s) have nothing installed at all. Installing those, and"
                        + " only those, before this container reports ready.",
                missing.withStatus(Change.Status.MISSING).size());
        final ApplyResult result;
        try {
            result = Runs.apply(config, missing, settings);
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

    /** Writes the readiness marker once the schema is current and keeps it fresh; every service waits for it. */
    private static void markReady() {
        final Readiness readiness = Readiness.onDefaultPath(CLOCK, log::warn);
        if (!readiness.keepBeating()) {
            // Fatal to everything waiting on this process, so it is loud.
            log.error(
                    "Could not write the readiness marker {}. The rest of the stack will not"
                            + " start, because its healthcheck reads this file.",
                    readiness.marker());
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

    /** The steward group for the report: from the database where it answers, else from the environment alone. */
    private static @Nullable StewardSpec reportConfig(final @Nullable Database opened) {
        try {
            if (opened != null) {
                try {
                    return StewardSettings.steward(StewardSettings.stored(opened.dataSource(), SETTINGS_LOG))
                            .get();
                } catch (final RuntimeException noSettingsYet) {
                    log.warn(
                            "The database holds no settings yet, so this report runs on the defaults: {}",
                            noSettingsYet.getMessage());
                }
            }
            return StewardSettings.steward(EnvironmentSettings.of(StewardSettings.ENVIRONMENT))
                    .get();
        } catch (final SettingsException broken) {
            // No stack trace, so the sentence is not missed.
            log.error("Refusing to run on settings that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    private static @Nullable DatabaseSpec databaseConfig() {
        try {
            return StewardSettings.database().get();
        } catch (final SettingsException broken) {
            log.error("Refusing to touch the database on settings that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    /** Prints a fresh VAPID keypair for {@code web.yml}'s web-push section, public half first. */
    private static int generateVapidKeys() {
        final com.interaso.webpush.VapidKeys keys = com.interaso.webpush.VapidKeys.generate();
        System.out.println(keys.getX509PublicKey());
        System.out.println(keys.getPkcs8PrivateKey());
        return 0;
    }

    /** Applies the schema on its own; {@code false} means the run must not continue. */
    private static boolean migrate() {
        final DatabaseSpec database = databaseConfig();
        if (database == null) {
            return false;
        }
        // The pool first, so "not up yet" does not look like a failed migration.
        final Database opened = DatabaseWaiting.openDatabase(database, Waiting.on(CLOCK));
        if (opened == null) {
            return false;
        }
        try (Database pool = opened) {
            Schema.migrate(pool, Schema.passwords(System.getenv()));
            return true;
        } catch (final RuntimeException failed) {
            // Flyway's message names the file and the statement.
            log.error("The database schema could not be applied. Nothing else was done.", failed);
            return false;
        }
    }
}
