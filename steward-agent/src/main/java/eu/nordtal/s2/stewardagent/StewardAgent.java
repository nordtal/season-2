package eu.nordtal.s2.stewardagent;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.Deployment;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxTable;
import eu.nordtal.s2.database.inbox.Inboxes;
import eu.nordtal.s2.database.inbox.StewardRequest;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.internalapi.InternalServer;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.settings.DatabaseSettings;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.DatabaseWaiting;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.stewardagent.backup.LocalSnapshots;
import eu.nordtal.s2.stewardagent.config.AgentSettings;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.docker.Containers;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerSocket;
import eu.nordtal.s2.stewardagent.plugin.PluginDirectory;
import eu.nordtal.s2.stewardagent.run.Bootstrap;
import eu.nordtal.s2.stewardagent.run.Runner;
import eu.nordtal.s2.stewardagent.run.UpdateServer;
import eu.nordtal.s2.stewardagent.schema.Schema;
import eu.nordtal.s2.stewardagent.topology.ComposeTopology;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one process that holds the Docker socket and the volumes and carries out every run, and the one that migrates.
 *
 * {@code serve} and {@code migrate} are services, {@code up} deploys for the setup script, the rest ask.
 */
public final class StewardAgent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StewardAgent.class);

    /** How long a settled request is kept in its inbox. */
    private static final Duration REQUEST_RETENTION = Duration.ofDays(30);

    private StewardAgent() {}

    public static void main(final String[] args) throws Exception {
        final String mode = args.length == 0 ? "serve" : args[0];
        final InternalServer server = new InternalServer(Compose.SELF, System::getenv);
        final String project = System.getenv("COMPOSE_PROJECT_NAME");
        final Compose compose = new Compose(
                Path.of(server.setting("COMPOSE_FILE", "/app/compose.yml")),
                Path.of(server.setting("ENV_FILE", "/app/env/.env")),
                Path.of(server.setting("PROJECT_DIRECTORY", "/app")),
                project == null || project.isBlank() ? Deployment.PROJECT : project);

        final Docker docker = new Docker(new DockerSocket(
                Path.of(server.setting("DOCKER_SOCKET", DockerSocket.DEFAULT_SOCKET.toString())),
                Duration.ofSeconds(30)));
        switch (mode) {
            case "up" -> System.exit(deploy(compose, List.of(), System.out::println, true, docker::hasImage));
            case "serve" -> System.exit(serve(server, compose, docker));
            case Compose.MIGRATE -> System.exit(migrate());
            case "request", "status" -> System.exit(HostRequests.run(args));
            default -> {
                System.err.println(
                        "usage: steward-agent [serve|migrate|up|request KIND [SERVICES] [MINUTES]|status ID]");
                System.exit(2);
            }
        }
    }

    /**
     * Pulls, then brings the services up, so a failed pull stops the deployment before anything is taken down.
     *
     * @param bootstrap {@code true} only for {@code agent up}, the throwaway container the setup script runs
     * @param isHere asks the daemon whether an image is already on this host
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

    /** Recreates one container from the image already here, never pulling. */
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

    /**
     * Names each service one deployment touches, never an empty list.
     *
     * An empty list would redeploy steward-agent, and a named request for it is refused rather than filtered.
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
     * Creates every service's role, applies every pending migration and exits, as the migrate service.
     *
     * @return the exit status, which every service with a database login waits on: 0 only on a current schema
     */
    private static int migrate() {
        final DatabaseSpec databaseConfig;
        try {
            databaseConfig = AgentSettings.database().get();
        } catch (final SettingsException broken) {
            log.error("Refusing to touch the database on settings that cannot be read: {}", broken.getMessage());
            return 1;
        }
        final Database opened =
                DatabaseWaiting.openDatabase(databaseConfig, Compose.MIGRATE, Waiting.on(NetworkTime.clock()));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            Schema.migrate(database, Schema.passwords(System.getenv()));
            return 0;
        } catch (final RuntimeException failure) {
            // A server must not start against an unknown schema, and every service waits for this one.
            log.error("The database schema could not be applied, so nothing that logs in will start.", failure);
            return 1;
        }
    }

    /**
     * Serves the API, installs what is missing, reports ready and carries out runs; the migrate service migrates.
     *
     * @return the exit status: 1 when the database or its settings are not there
     */
    private static int serve(final InternalServer server, final Compose compose, final Docker docker)
            throws java.io.IOException {
        // A stale env file shows in the boot log, not on the first deploy.
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
            return serveWithDatabase(server, compose, docker, clock, database, databaseConfig, settings, runs);
        }
    }

    /**
     * Deletes the settled requests of every inbox but the run inbox older than {@link #REQUEST_RETENTION}.
     * The runs are kept: they are the history Steward shows. Once at startup, as the owner, which deletes from
     * inboxes no other role may.
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
                // Not fatal: an inbox that keeps its old rows is still an inbox.
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
        if (database.dataSource() instanceof com.zaxxer.hikari.HikariDataSource pool
                && pool.getHikariPoolMXBean() != null) {
            pool.getHikariPoolMXBean().softEvictConnections();
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

    // The one volumes root is the runs group's, so what a run installs and what the API reads cannot differ.
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
            final Clock clock,
            final Database database,
            final DatabaseSpec databaseConfig,
            final DatabaseSettings settings,
            final Setting<RunSpec> runs) {
        final String project = compose.projectName();
        final AgentApi.Paths paths = pathsOf(server, runs.get());
        final PluginDirectory plugins = PluginDirectory.using(database.dataSource());
        final AgentApi api = new AgentApi(docker, project, compose::definitions, compose::hashes, paths, clock);
        final RunRoutes runRoutes = new RunRoutes(runs::get, plugins, database, clock);
        if (!docker.isReachable()) {
            log.warn("No docker socket answers, so every container route answers that the daemon is not answering.");
        }
        api.start();
        // Without its secret it does not start: an open process that can recreate every container is a root shell.
        final Javalin app = server.start(AgentWire.PORT, config -> {
            api.register(config);
            runRoutes.register(config);
        });
        try (api) {
            // Before the marker and before any run is claimed, so nothing races it.
            if (runs.get().bootstrap()) {
                Bootstrap.installMissing(runs.get(), database);
            }
            if (!Readiness.onDefaultPath(clock, log::warn).keepBeating()) {
                log.error("Could not write the readiness marker, so the rest of the stack will not start.");
            }
            final UpdateDirectory updates = UpdateDirectory.using(database.dataSource());
            final ComposeTopology topology = new ComposeTopology(
                    compose::definitions, paths.backupSources().toString(), clock);
            final LocalStack stack =
                    new LocalStack(docker, new Containers(docker, project, compose::hashes), topology, compose);
            final LocalSnapshots snapshots = snapshotsOf(docker, compose, paths, clock, runs, database);
            try (SignalHub signals = SignalHub.open(
                            databaseConfig.jdbcUrl(),
                            databaseConfig.username(),
                            databaseConfig.password(),
                            databaseConfig.queryTimeoutSeconds(),
                            "steward-agent-signals",
                            log);
                    UpdateServer loop = new UpdateServer(
                            updates,
                            new Runner(
                                    runs.get(),
                                    database,
                                    stack,
                                    snapshots,
                                    updates,
                                    Waiting.on(clock),
                                    plugins,
                                    runRoutes.removal()),
                            clock)) {
                loop.listen(signals);
                settings.listen(signals, () -> reload(runs));
                signals.start();
                // SIGTERM is how a redeploy asks; without this the container is killed after the grace period.
                Runtime.getRuntime().addShutdownHook(new Thread(loop::close, "steward-agent-shutdown"));
                loop.serve();
            }
        } finally {
            app.stop();
        }
        return 0;
    }

    /** The backups and restores, which run the migrate service again after a dump has replaced the database. */
    private static LocalSnapshots snapshotsOf(
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
                () -> afterDatabaseRestore(database, compose));
    }

    /** Takes the runs group again after a change in Steward; a refused change keeps the values in use. */
    private static void reload(final Setting<RunSpec> runs) {
        try {
            runs.reload();
        } catch (final SettingsException broken) {
            log.warn("The runs settings were changed but refused, so the last ones stay: {}", broken.getMessage());
        }
    }
}
