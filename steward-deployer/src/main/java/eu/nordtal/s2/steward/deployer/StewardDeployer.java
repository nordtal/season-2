package eu.nordtal.s2.steward.deployer;

import com.google.gson.Gson;
import eu.nordtal.s2.common.time.NetworkTime;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinGson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one service allowed to create containers, from the {@code compose.yml} baked into its image.
 *
 * {@code deployer up} is the setup script's blocking run; {@code deployer serve} is steward-ui's API.
 */
public final class StewardDeployer {

    private static final Logger log = LoggerFactory.getLogger(StewardDeployer.class);

    private static final int DEFAULT_PORT = 8081;

    private StewardDeployer() {}

    public static void main(final String[] args) throws Exception {
        final String mode = args.length == 0 ? "serve" : args[0];
        final Compose compose = new Compose(
                path("NORDTAL_STEWARD_COMPOSE_FILE", "/app/compose.yml"),
                path("NORDTAL_STEWARD_ENV_FILE", "/app/env/.env"),
                path("NORDTAL_STEWARD_PROJECT_DIRECTORY", "/app"),
                env("COMPOSE_PROJECT_NAME", "nordtal-s2"));

        switch (mode) {
            case "up" -> System.exit(deploy(compose, List.of(), System.out::println, true));
            case "serve" -> serve(compose);
            default -> {
                System.err.println("usage: steward-deployer [serve|up]");
                System.exit(2);
            }
        }
    }

    /** Pulls, then brings the services up, so a failed pull stops the deployment before anything is taken down. */
    static int deploy(
            final Compose compose, final List<String> requested, final java.util.function.Consumer<String> output)
            throws Exception {
        return deploy(compose, requested, output, false);
    }

    /**
     * Deploys, optionally allowing steward-deployer itself.
     *
     * @param bootstrap {@code true} only for {@code deployer up}, the throwaway container the setup script runs
     */
    static int deploy(
            final Compose compose,
            final List<String> requested,
            final java.util.function.Consumer<String> output,
            final boolean bootstrap)
            throws Exception {
        final List<String> services = servicesToDeploy(compose.services().keySet(), requested, bootstrap);

        for (final String service : services) {
            final Compose.PullOutcome outcome = compose.pull(service, output);
            if (outcome == Compose.PullOutcome.FAILED) {
                output.accept("no image for " + service + ", from the registry or from this host. "
                        + "Nothing has been stopped.");
                return 1;
            }
        }
        return bootstrap ? compose.bootstrap(services, output) : compose.up(services, output);
    }

    /** Recreates one container from the image already here, never pulling. */
    static int recreate(final Compose compose, final String service, final java.util.function.Consumer<String> output)
            throws Exception {
        if (!compose.hasLocalImage(service)) {
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
     * An empty list would redeploy steward-deployer, and a named request for it is refused rather than filtered.
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

    private static void serve(final Compose compose) throws java.io.IOException {
        final String token = requireToken();
        // A stale env file shows in the boot log, not on the first deploy.
        compose.assertEnvFileFresh();
        final Jobs jobs = new Jobs(NetworkTime.clock());

        Javalin.create(config -> configureRoutes(config, compose, jobs, token)).start(port());

        log.info("steward-deployer listening on {}", port());
    }

    private static String requireToken() {
        final String token = System.getenv("NORDTAL_STEWARD_DEPLOYER_TOKEN");
        if (token == null || token.isBlank()) {
            // An unauthenticated process that can recreate every container is a remote root shell.
            throw new IllegalStateException(
                    "NORDTAL_STEWARD_DEPLOYER_TOKEN is not set. steward-deployer creates containers "
                            + "and will not serve without a shared secret; the setup script writes one.");
        }
        return token;
    }

    private static void configureRoutes(
            final io.javalin.config.JavalinConfig config, final Compose compose, final Jobs jobs, final String token) {
        config.jsonMapper(new JavalinGson(new Gson(), true));
        config.startup.showJavalinBanner = false;

        config.routes.before("/api/*", ctx -> {
            if (ctx.path().equals("/api/health")) {
                return;
            }
            if (!token.equals(ctx.header("X-Steward-Token"))) {
                throw new io.javalin.http.UnauthorizedResponse("bad or missing X-Steward-Token");
            }
        });

        config.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        // The deployer's own answer to "did my deployment arrive".
        config.routes.get(
                "/api/state", ctx -> ctx.contentType("application/json").result(compose.state()));

        config.routes.get("/api/services", ctx -> ctx.json(compose.services()));

        config.routes.post("/api/deploy", ctx -> deployRoute(ctx, compose, jobs));
        config.routes.post("/api/recreate/{service}", ctx -> recreateRoute(ctx, compose, jobs));
        config.routes.get(
                "/api/jobs",
                ctx -> ctx.json(jobs.all().stream().map(Jobs.Job::summary).toList()));
        config.routes.get("/api/jobs/{id}", ctx -> jobRoute(ctx, jobs));

        // SSE: one direction, and no special reverse proxy rule.
        config.routes.sse("/api/jobs/{id}/stream", client -> streamRoute(client, jobs));
    }

    private static void deployRoute(final io.javalin.http.Context ctx, final Compose compose, final Jobs jobs)
            throws Exception {
        final Request request = ctx.bodyAsClass(Request.class);
        final List<String> services = request == null || request.services == null ? List.of() : request.services;
        final Jobs.Job job = jobs.start("deploy", services, output -> deploy(compose, services, output));
        ctx.status(HttpStatus.ACCEPTED).json(job.summary());
    }

    private static void recreateRoute(final io.javalin.http.Context ctx, final Compose compose, final Jobs jobs) {
        final String service = ctx.pathParam("service");
        final Jobs.Job job = jobs.start("recreate", List.of(service), output -> recreate(compose, service, output));
        ctx.status(HttpStatus.ACCEPTED).json(job.summary());
    }

    private static void jobRoute(final io.javalin.http.Context ctx, final Jobs jobs) {
        final Jobs.Job job = jobs.get(ctx.pathParam("id"));
        if (job == null) {
            throw new io.javalin.http.NotFoundResponse("no such job");
        }
        final Map<String, Object> answer = new java.util.LinkedHashMap<>(job.summary());
        answer.put("lines", job.lines());
        ctx.json(answer);
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

    /** The body of {@code POST /api/deploy}: an empty list means the whole project. */
    private static final class Request {
        private @Nullable List<String> services;
    }

    private static int port() {
        final String value = System.getenv("NORDTAL_STEWARD_DEPLOYER_PORT");
        return value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value.trim());
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path path(final String name, final String fallback) {
        return Path.of(env(name, fallback));
    }
}
