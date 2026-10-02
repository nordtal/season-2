package eu.nordtal.s2.stewardagent;

import eu.nordtal.s2.common.Deployment;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.internalapi.InternalServer;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerSocket;
import io.javalin.http.HttpStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one process that holds the Docker socket and the volumes, and the only one allowed to create containers.
 *
 * {@code agent up} is the setup script's blocking run; {@code agent serve} is the API steward calls.
 */
public final class StewardAgent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StewardAgent.class);

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
            case "serve" -> serve(server, compose, docker);
            default -> {
                System.err.println("usage: steward-agent [serve|up]");
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
            if (!requested.isEmpty() && services.contains(Compose.SELF)) {
                throw new IllegalArgumentException(Compose.SELF + " will not recreate itself - the"
                        + " new container would kill the process writing this report. Renewing it"
                        + " is what deploy/nordtal.sh does, from a throwaway container.");
            }
            services.remove(Compose.SELF);
        }
        return List.copyOf(services);
    }

    private static void serve(final InternalServer server, final Compose compose, final Docker docker)
            throws java.io.IOException {
        // A stale env file shows in the boot log, not on the first deploy.
        compose.assertEnvFileFresh();
        final Clock clock = NetworkTime.clock();
        final String project = compose.projectName();
        final Jobs jobs = new Jobs(clock);
        final AgentApi api = new AgentApi(
                docker,
                project,
                compose::definitions,
                new AgentApi.Paths(
                        Path.of(server.setting("VOLUMES_ROOT", String.valueOf(AgentApi.Paths.DEFAULTS.volumesRoot()))),
                        Path.of(server.setting(
                                "BACKUP_SOURCES",
                                AgentApi.Paths.DEFAULTS.backupSources().toString())),
                        Path.of(server.setting(
                                "BACKUPS", AgentApi.Paths.DEFAULTS.backups().toString()))),
                clock);
        if (!docker.isReachable()) {
            log.warn("No docker socket answers, so every container route answers that the daemon is not answering.");
        }
        api.start();
        // Without its secret it does not start: an open process that can recreate every container is a root shell.
        final var app = server.serve(AgentWire.PORT, clock, config -> {
            api.register(config);
            jobRoutes(config, compose, jobs, docker);
        });
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            api.close();
                            app.stop();
                        },
                        "steward-agent-shutdown"));
    }

    private static void jobRoutes(
            final io.javalin.config.JavalinConfig config, final Compose compose, final Jobs jobs, final Docker docker) {
        config.routes.post(AgentWire.DEPLOY, ctx -> deployRoute(ctx, compose, jobs, docker));
        config.routes.post(AgentWire.RECREATE, ctx -> recreateRoute(ctx, compose, jobs, docker));
        config.routes.get(
                AgentWire.JOBS,
                ctx -> ctx.json(jobs.all().stream().map(job -> job.wire(false)).toList()));
        config.routes.get(AgentWire.JOB, ctx -> jobRoute(ctx, jobs));

        // SSE: one direction, and no special reverse proxy rule.
        config.routes.sse(AgentWire.JOB + "/stream", client -> streamRoute(client, jobs));
    }

    private static void deployRoute(
            final io.javalin.http.Context ctx, final Compose compose, final Jobs jobs, final Docker docker) {
        final AgentWire.Deploy request = ctx.bodyAsClass(AgentWire.Deploy.class);
        final List<String> services = request == null || request.services() == null ? List.of() : request.services();
        final Jobs.Job job =
                jobs.start("deploy", services, output -> deploy(compose, services, output, false, docker::hasImage));
        ctx.status(HttpStatus.ACCEPTED).json(job.wire(false));
    }

    private static void recreateRoute(
            final io.javalin.http.Context ctx, final Compose compose, final Jobs jobs, final Docker docker) {
        final String service = ctx.pathParam("service");
        final Jobs.Job job = jobs.start(
                "recreate", List.of(service), output -> recreate(compose, service, output, docker::hasImage));
        ctx.status(HttpStatus.ACCEPTED).json(job.wire(false));
    }

    private static void jobRoute(final io.javalin.http.Context ctx, final Jobs jobs) {
        final Jobs.Job job = jobs.get(ctx.pathParam("id"));
        if (job == null) {
            throw new io.javalin.http.NotFoundResponse("no such job");
        }
        ctx.json(job.wire(true));
    }

    private static void streamRoute(final io.javalin.http.sse.SseClient client, final Jobs jobs) {
        final Jobs.Job job = jobs.get(client.ctx().pathParam("id"));
        if (job == null) {
            client.close();
            return;
        }
        client.keepAlive();
        final Runnable stop = job.follow(line -> client.sendEvent("line", line));
        client.onClose(stop);
    }
}
