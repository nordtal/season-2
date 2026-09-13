package eu.nordtal.s2.steward.deployer;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinGson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one service allowed to create containers (§8a, §8b).
 *
 * <p>It carries {@code compose.yml} inside its own image, so a change to the deployment is a new
 * image of this service rather than a file somebody edited on the host - the failure mode the
 * workspace guide warns about for Arcane's GitOps checkout, where the next sync quietly took the
 * edit back.</p>
 *
 * <p>Two ways in, for two callers who are not alike:</p>
 * <ul>
 *   <li>{@code deployer up} - the setup script on the host, which runs before anything else exists
 *       and has no interface to click in. It waits for the deployment and exits with its code.</li>
 *   <li>{@code deployer serve} - the small HTTP API steward-ui calls. It never waits: a deployment
 *       is a job, and the caller reads its output as it appears.</li>
 * </ul>
 *
 * <p><b>The serving process never recreates itself.</b> {@link Compose#SELF} is refused wherever
 * the API accepts a service name, and the whole-stack deployment names every service one by one so
 * that it can be left out - an empty list would mean "all of them" to compose and put it back.
 * Renewing this container is the setup script's job (§9c), which is why {@code deployer up} - and
 * only it, running as a throwaway container beside the stack - goes through
 * {@link Compose#bootstrap}.</p>
 */
public final class StewardDeployer {

    private static final Logger log = LoggerFactory.getLogger(StewardDeployer.class);

    private static final int DEFAULT_PORT = 8081;

    private StewardDeployer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "serve" : args[0];
        Compose compose = new Compose(
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

    /**
     * Pull, then up.
     *
     * <p>The order is the whole point of doing it here rather than leaving it to {@code up}: a pull
     * that fails has to stop the deployment <b>before</b> anything is taken down, not halfway
     * through with the servers already stopped. The one tolerated exception is documented on
     * {@link Compose#pull}.</p>
     */
    static int deploy(Compose compose, List<String> requested, java.util.function.Consumer<String> output)
            throws Exception {
        return deploy(compose, requested, output, false);
    }

    /**
     * @param bootstrap {@code true} only for {@code deployer up}: the throwaway container the setup
     *                  script runs, which is allowed to create steward-deployer because it is not
     *                  the compose-managed one. Every other caller is, and must not.
     */
    static int deploy(Compose compose, List<String> requested,
                      java.util.function.Consumer<String> output, boolean bootstrap)
            throws Exception {
        List<String> services = servicesToDeploy(compose.services().keySet(), requested, bootstrap);

        for (String service : services) {
            Compose.PullOutcome outcome = compose.pull(service, output);
            if (outcome == Compose.PullOutcome.FAILED) {
                output.accept("no image for " + service + ", from the registry or from this host. "
                        + "Nothing has been stopped.");
                return 1;
            }
        }
        return bootstrap ? compose.bootstrap(services, output) : compose.up(services, output);
    }

    /**
     * Which services one deployment touches, named one by one.
     *
     * <p><b>Never an empty list, and that is the point.</b> An empty list of service names means
     * <i>every</i> service to {@code docker compose up}. Passing one on the whole-stack path
     * therefore put steward-deployer back in after it had just been taken out - so the service that
     * must never recreate itself did exactly that on the most ordinary deployment there is, and the
     * new container would have killed the process still writing the report.</p>
     */
    static List<String> servicesToDeploy(java.util.Collection<String> all, List<String> requested,
                                         boolean bootstrap) {
        List<String> services = requested.isEmpty() ? new ArrayList<>(all) : new ArrayList<>(requested);
        if (!bootstrap) {
            services.remove(Compose.SELF);
        }
        return List.copyOf(services);
    }

    private static void serve(Compose compose) {
        String token = System.getenv("NORDTAL_STEWARD_DEPLOYER_TOKEN");
        if (token == null || token.isBlank()) {
            // Refusing to start is the point. This process can recreate every container in the
            // stack; an unauthenticated one on a shared network is a remote root shell with extra
            // steps, and a service that merely logs a warning about that gets deployed anyway.
            throw new IllegalStateException(
                    "NORDTAL_STEWARD_DEPLOYER_TOKEN is not set. steward-deployer creates containers "
                    + "and will not serve without a shared secret; the setup script writes one.");
        }
        Jobs jobs = new Jobs();

        Javalin.create(config -> {
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

            // What compose thinks is running. steward-ui draws the service list from the worker's
            // docker view, not from here - this is the deployer's own answer to "did my deployment
            // arrive", which is a different question.
            config.routes.get("/api/state", ctx -> ctx.contentType("application/json")
                    .result(compose.state()));

            config.routes.get("/api/services", ctx -> ctx.json(compose.services()));

            config.routes.post("/api/deploy", ctx -> {
                Request request = ctx.bodyAsClass(Request.class);
                List<String> services = request == null || request.services == null
                        ? List.of() : request.services;
                Jobs.Job job = jobs.start("deploy", services,
                        output -> deploy(compose, services, output));
                ctx.status(HttpStatus.ACCEPTED).json(job.summary());
            });

            config.routes.post("/api/recreate/{service}", ctx -> {
                String service = ctx.pathParam("service");
                Jobs.Job job = jobs.start("recreate", List.of(service), output -> {
                    Compose.PullOutcome outcome = compose.pull(service, output);
                    if (outcome == Compose.PullOutcome.FAILED) {
                        output.accept("no image for " + service + ". Nothing has been stopped.");
                        return 1;
                    }
                    return compose.recreate(service, output);
                });
                ctx.status(HttpStatus.ACCEPTED).json(job.summary());
            });

            config.routes.get("/api/jobs", ctx ->
                    ctx.json(jobs.all().stream().map(Jobs.Job::summary).toList()));

            config.routes.get("/api/jobs/{id}", ctx -> {
                Jobs.Job job = jobs.get(ctx.pathParam("id"));
                if (job == null) {
                    throw new io.javalin.http.NotFoundResponse("no such job");
                }
                Map<String, Object> answer = new java.util.LinkedHashMap<>(job.summary());
                answer.put("lines", job.lines());
                ctx.json(answer);
            });

            // SSE rather than a websocket: one direction, reconnects by itself, and it passes
            // through a reverse proxy without a special rule.
            config.routes.sse("/api/jobs/{id}/stream", client -> {
                Jobs.Job job = jobs.get(client.ctx().pathParam("id"));
                if (job == null) {
                    client.close();
                    return;
                }
                client.keepAlive();
                Runnable stop = job.follow(line -> client.sendEvent("line", line));
                client.onClose(stop::run);
            });
        }).start(port());

        log.info("steward-deployer listening on {}", port());
    }

    /** The body of {@code POST /api/deploy}: an empty list means the whole project. */
    private static final class Request {
        private List<String> services;
    }

    private static int port() {
        String value = System.getenv("NORDTAL_STEWARD_DEPLOYER_PORT");
        return value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value.trim());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path path(String name, String fallback) {
        return Path.of(env(name, fallback));
    }
}
