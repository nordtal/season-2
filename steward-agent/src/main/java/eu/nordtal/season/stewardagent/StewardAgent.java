package eu.nordtal.season.stewardagent;

import eu.nordtal.season.common.Deployment;
import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.ProcessScheduler;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.game.GameDataStore;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxTable;
import eu.nordtal.season.database.inbox.Inboxes;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.internalapi.InternalServer;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.DatabaseWaiting;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.stewardagent.backup.LocalSnapshots;
import eu.nordtal.season.stewardagent.backup.OffsiteCopy;
import eu.nordtal.season.stewardagent.config.AgentSettings;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.descriptor.LocalJars;
import eu.nordtal.season.stewardagent.docker.Containers;
import eu.nordtal.season.stewardagent.docker.Docker;
import eu.nordtal.season.stewardagent.docker.DockerSocket;
import eu.nordtal.season.stewardagent.gamedata.GameAssets;
import eu.nordtal.season.stewardagent.plugin.PluginDirectory;
import eu.nordtal.season.stewardagent.run.Bootstrap;
import eu.nordtal.season.stewardagent.run.PluginRemoval;
import eu.nordtal.season.stewardagent.run.Runner;
import eu.nordtal.season.stewardagent.run.UpdateServer;
import eu.nordtal.season.stewardagent.schema.Schema;
import eu.nordtal.season.stewardagent.topology.ComposeTopology;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The process that holds the Docker socket and the volumes, carries out every run and runs the migration.
 *
 * {@code serve} and {@code migrate} are services, {@code up} deploys for the setup script, the rest ask.
 */
public final class StewardAgent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StewardAgent.class);

    private static final Duration REQUEST_RETENTION = Duration.ofDays(30);

    private static final Duration MIGRATION_QUERY_BOUND = Duration.ofMinutes(30);

    private StewardAgent() {}

    public static void main(final String[] args) throws Exception {
        final String mode = args.length == 0 ? "serve" : args[0];
        final InternalServer server = new InternalServer(Compose.SELF, System::getenv);
        final String project = System.getenv("COMPOSE_PROJECT_NAME");
        final Compose compose = new Compose(
                Path.of(server.setting("COMPOSE_FILE", Compose.IN_IMAGE)),
                Path.of(server.setting("ENV_FILE", "/app/env/.env")),
                Path.of(server.setting("PROJECT_DIRECTORY", "/app")),
                project == null || project.isBlank() ? Deployment.PROJECT : project);

        final ProcessScheduler scheduler =
                new ProcessScheduler(Compose.SELF, failure -> log.error("A task of steward-agent failed", failure));
        final Docker docker = new Docker(new DockerSocket(
                Path.of(server.setting("DOCKER_SOCKET", DockerSocket.DEFAULT_SOCKET.toString())),
                Duration.ofSeconds(30),
                scheduler));
        switch (mode) {
            case "up" -> System.exit(deploy(compose, List.of(), System.out::println, true, docker::hasImage));
            case "serve" -> System.exit(serve(server, compose, docker, scheduler));
            case Compose.MIGRATE -> System.exit(migrate());
            case "run" ->
                System.exit(args.length == 2 ? runHanded(server, compose, docker, Long.parseLong(args[1])) : 2);
            case "request", "status" -> System.exit(HostRequests.run(args));
            default -> {
                System.err.println(
                        "usage: steward-agent [serve|migrate|run ID|up|request KIND [SERVICES] [MINUTES]|status ID]");
                System.exit(2);
            }
        }
    }

    /**
     * Pulls every image, then starts the services, so a failed pull stops the deployment first.
     *
     * @param bootstrap {@code true} only for {@code agent up}
     * @param isHere asks the daemon whether an image is on this host
     */
    static int deploy(
            final Compose compose,
            final List<String> requested,
            final java.util.function.Consumer<String> output,
            final boolean bootstrap,
            final Predicate<String> isHere)
            throws Exception {
        final List<String> services = servicesToDeploy(compose.services().keySet(), requested, bootstrap);

        for (final String service : services) {
            final Compose.PullOutcome outcome = compose.pull(service, output, isHere);
            if (outcome == Compose.PullOutcome.FAILED) {
                output.accept("no image for " + service + ", from the registry or from this host. "
                        + "Nothing has been stopped.");
                return 1;
            }
        }
        return bootstrap ? compose.bootstrap(services, output) : compose.up(services, output);
    }

    static int recreate(
            final Compose compose,
            final String service,
            final java.util.function.Consumer<String> output,
            final Predicate<String> isHere)
            throws Exception {
        if (!compose.hasLocalImage(service, isHere)) {
            output.accept("no image for " + service + " on this host, and recreate does not fetch "
                    + "one. Deploy " + service + " instead - that is the button that pulls. "
                    + "Nothing has been stopped.");
            return 1;
        }
        return compose.recreate(service, output);
    }

    /** Creates a standby from the local image and pulls the image only when this host has none. */
    static int standby(
            final Compose compose,
            final String service,
            final java.util.function.Consumer<String> output,
            final Predicate<String> isHere)
            throws Exception {
        if (!compose.hasLocalImage(service, isHere)
                && compose.pull(service, output, isHere) == Compose.PullOutcome.FAILED) {
            output.accept("no image for " + service + ", from the registry or from this host. "
                    + "Nothing has been stopped.");
            return 1;
        }
        return compose.recreate(service, output);
    }

    /**
     * Names each service one deployment touches, never an empty list.
     *
     * An empty list would deploy steward-agent, and a request that names it is refused.
     */
    static List<String> servicesToDeploy(
            final java.util.Collection<String> all, final List<String> requested, final boolean bootstrap) {
        final List<String> services = requested.isEmpty() ? new ArrayList<>(all) : new ArrayList<>(requested);
        if (!bootstrap) {
            requested.forEach(Compose::refuseSelf);
            services.remove(Compose.SELF);
        }
        return List.copyOf(services);
    }

    /**
     * Creates the roles, applies the pending migrations and exits.
     *
     * @return 0 only on a current schema
     */
    private static int migrate() {
        final DatabaseSpec databaseConfig;
        try {
            databaseConfig = AgentSettings.database().get();
        } catch (final SettingsException broken) {
            log.error("Refusing to touch the database on settings that cannot be read: {}", broken.getMessage());
            return 1;
        }
        final Database opened = DatabaseWaiting.openDatabase(
                databaseConfig, Compose.MIGRATE, MIGRATION_QUERY_BOUND, Waiting.on(NetworkTime.clock()));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            Schema.migrate(database, Schema.passwords(System.getenv()));
            return 0;
        } catch (final RuntimeException failure) {
            log.error("The database schema could not be applied, so nothing that logs in will start.", failure);
            return 1;
        }
    }

    /**
     * Carries out the run the long-running steward-agent handed to this one-shot, then exits.
     *
     * @return 0 once the run is settled, 1 without such a run or without a database
     */
    private static int runHanded(
            final InternalServer server, final Compose compose, final Docker docker, final long id) {
        final Path backups = Path.of(
                server.setting("BACKUPS", AgentApi.Paths.DEFAULTS.backups().toString()));
        // The container is removed on exit, so the file is the only record of a failed run.
        try (RunLog ignored = RunLog.keep(backups, id)) {
            return carryOutHanded(server, compose, docker, id);
        }
    }

    private static int carryOutHanded(
            final InternalServer server, final Compose compose, final Docker docker, final long id) {
        final Clock clock = NetworkTime.clock();
        final DatabaseSpec databaseConfig;
        try {
            databaseConfig = AgentSettings.database().get();
        } catch (final SettingsException broken) {
            log.error("Refusing to touch the database on settings that cannot be read: {}", broken.getMessage());
            return 1;
        }
        final Database opened = DatabaseWaiting.openDatabase(databaseConfig, compose.oneShotName(), Waiting.on(clock));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            final Setting<RunSpec> runs;
            try {
                runs = AgentSettings.runs(AgentSettings.stored(database.dataSource(), log));
            } catch (final SettingsException broken) {
                log.error("Refusing to carry out a run on settings that cannot be read: {}", broken.getMessage());
                return 1;
            }
            final UpdateDirectory updates = Schema.updates(database);
            final Runner runner = runner(server, compose, docker, clock, database, runs, updates, PluginRemoval.NONE)
                    .asOneShot();
            final UpdateServer handed = new UpdateServer(updates, runner, docker::isRunning, clock);
            return handed.carryOutHanded(id, compose.oneShotName()) ? 0 : 1;
        }
    }

    private static Runner runner(
            final InternalServer server,
            final Compose compose,
            final Docker docker,
            final Clock clock,
            final Database database,
            final Setting<RunSpec> runs,
            final UpdateDirectory updates,
            final PluginRemoval removal) {
        final AgentApi.Paths paths = pathsOf(server, runs.get());
        final ComposeTopology topology =
                new ComposeTopology(compose::definitions, paths.backupSources().toString(), clock);
        final LocalStack stack = new LocalStack(
                docker,
                new Containers(docker, compose.projectName(), compose::hashes, new LocalJars(paths.volumesRoot())::of),
                topology,
                compose);
        return new Runner(
                runs.get(),
                database,
                stack,
                snapshotsOf(server, docker, compose, paths, clock, runs, database),
                updates,
                Waiting.on(clock),
                PluginDirectory.using(database.dataSource()),
                removal);
    }

    /**
     * Serves the API, installs what is missing and carries out runs.
     *
     * @return 1 without the database or its settings
     */
    private static int serve(
            final InternalServer server, final Compose compose, final Docker docker, final Scheduler scheduler)
            throws java.io.IOException {
        compose.assertEnvFileFresh();
        final Clock clock = NetworkTime.clock();
        final DatabaseSpec databaseConfig;
        try {
            databaseConfig = AgentSettings.database().get();
        } catch (final SettingsException broken) {
            log.error("Refusing to touch the database on settings that cannot be read: {}", broken.getMessage());
            return 1;
        }
        final Database opened = DatabaseWaiting.openDatabase(databaseConfig, Compose.SELF, Waiting.on(clock));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            clearOldRequests(database);
            final DatabaseSettings settings = AgentSettings.stored(database.dataSource(), log);
            final Setting<RunSpec> runs;
            try {
                runs = AgentSettings.runs(settings);
            } catch (final SettingsException broken) {
                log.error("Refusing to carry out runs on settings that cannot be read: {}", broken.getMessage());
                return 1;
            }
            return serveWithDatabase(
                    server, compose, docker, scheduler, clock, database, databaseConfig, settings, runs);
        }
    }

    /**
     * Deletes the settled requests older than {@link #REQUEST_RETENTION} from every inbox except the run inbox.
     *
     * It runs once at startup as the owner, because no other role may delete from the inboxes.
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
                log.warn("Could not clear out old requests from {}; they stay where they are.", table, failure);
            }
        }
    }

    /**
     * A replaced database: the pool's connections go, since they cached plans of dropped tables, then the schema.
     *
     * @throws IllegalStateException if the migrate service could not bring the dump up to this release
     */
    private static void afterDatabaseRestore(final Database database, final Compose compose) {
        if (database.dataSource().getHikariPoolMXBean() != null) {
            database.dataSource().getHikariPoolMXBean().softEvictConnections();
        }
        final int status;
        try {
            status = compose.migrate(log::info);
        } catch (final java.io.IOException failure) {
            throw new IllegalStateException("the migrate service did not run: " + failure.getMessage(), failure);
        }
        if (status != 0) {
            throw new IllegalStateException("the migrate service exited with " + status + "; its log says why");
        }
    }

    private static AgentApi.Paths pathsOf(final InternalServer server, final RunSpec runs) {
        return new AgentApi.Paths(
                Path.of(runs.volumesRoot()),
                Path.of(server.setting(
                        "CONFIGS", AgentApi.Paths.DEFAULTS.configs().toString())),
                Path.of(server.setting(
                        "BACKUP_SOURCES",
                        AgentApi.Paths.DEFAULTS.backupSources().toString())),
                Path.of(server.setting(
                        "BACKUPS", AgentApi.Paths.DEFAULTS.backups().toString())));
    }

    private static int serveWithDatabase(
            final InternalServer server,
            final Compose compose,
            final Docker docker,
            final Scheduler scheduler,
            final Clock clock,
            final Database database,
            final DatabaseSpec databaseConfig,
            final DatabaseSettings settings,
            final Setting<RunSpec> runs) {
        final String project = compose.projectName();
        final AgentApi.Paths paths = pathsOf(server, runs.get());
        final PluginDirectory plugins = PluginDirectory.using(database.dataSource());
        final AgentApi api =
                new AgentApi(docker, project, compose::definitions, compose::hashes, paths, clock, scheduler);
        final RunRoutes runRoutes = new RunRoutes(runs::get, plugins, database, clock, api.topology()::read);
        if (!docker.isReachable()) {
            log.warn("No docker socket answers, so every container route answers that the daemon is not answering.");
        }
        api.start();
        // The agent refuses to start without its secret.
        final Javalin app = server.start(AgentWire.PORT, config -> {
            api.register(config);
            runRoutes.register(config);
        });
        try (api) {
            if (runs.get().bootstrap()) {
                Bootstrap.installMissing(
                        runs.get(), database, api.topology()::read, clock, services -> handOver(compose, services));
            }
            if (!Readiness.onDefaultPath(clock, log::warn).keepBeating(scheduler)) {
                log.error("Could not write the readiness marker, so the rest of the stack will not start.");
            }
            final UpdateDirectory updates = Schema.updates(database);
            try (SignalHub signals = SignalHub.open(
                            databaseConfig.jdbcUrl(),
                            databaseConfig.username(),
                            databaseConfig.password(),
                            databaseConfig.queryTimeoutSeconds(),
                            "steward-agent-signals",
                            log);
                    UpdateServer loop = new UpdateServer(
                            updates,
                            runner(server, compose, docker, clock, database, runs, updates, runRoutes.removal()),
                            docker::isRunning,
                            clock);
                    GameAssets icons = gameAssets(database, runs, clock)) {
                loop.listen(signals);
                icons.listen(signals);
                settings.listen(signals, () -> reload(runs));
                signals.start();
                icons.start();
                Runtime.getRuntime().addShutdownHook(new Thread(loop::close, "steward-agent-shutdown"));
                loop.serve();
            }
        } finally {
            app.stop();
        }
        return 0;
    }

    private static GameAssets gameAssets(final Database database, final Setting<RunSpec> runs, final Clock clock) {
        return GameAssets.fromMojang(
                GameDataStore.using(database.dataSource()),
                Path.of(System.getProperty("java.io.tmpdir"), "game-assets"),
                Duration.ofSeconds(runs.get().httpTimeoutSeconds()),
                Duration.ofSeconds(runs.get().downloadTimeoutSeconds()),
                () -> runs.get().mojangAssets(),
                clock);
    }

    private static LocalSnapshots snapshotsOf(
            final InternalServer server,
            final Docker docker,
            final Compose compose,
            final AgentApi.Paths paths,
            final Clock clock,
            final Setting<RunSpec> runs,
            final Database database) {
        return new LocalSnapshots(
                docker,
                compose.projectName(),
                paths.backupSources(),
                paths.backups(),
                clock,
                runs::get,
                () -> afterDatabaseRestore(database, compose),
                () -> offsiteTarget(server, compose));
    }

    private static Optional<OffsiteCopy.Target> offsiteTarget(final InternalServer server, final Compose compose) {
        final String repository = server.setting("OFFSITE_REPOSITORY", "");
        if (repository.isEmpty()) {
            return Optional.empty();
        }
        final Path environment = Path.of(server.setting("ENV_FILE", "/app/env/.env"));
        final Path directory = environment.toAbsolutePath().getParent();
        final Path keys = directory == null ? Path.of("/app/env") : directory;
        return Optional.of(new OffsiteCopy.Target(
                repository,
                server.setting("OFFSITE_PASSWORD", ""),
                keys.resolve("offsite-key"),
                keys.resolve("offsite-known-hosts"),
                compose.projectName()));
    }

    private static void reload(final Setting<RunSpec> runs) {
        try {
            runs.reload();
        } catch (final SettingsException broken) {
            log.warn("The runs settings were changed but refused, so the last ones stay: {}", broken.getMessage());
        }
    }

    private static void handOver(final Compose compose, final List<String> services) {
        try {
            compose.handOver(services, line -> log.info("[hand-over] {}", line));
        } catch (final java.io.IOException failure) {
            log.warn("could not hand {}'s volumes to their users: {}", services, failure.getMessage());
        }
    }
}
