package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.docker.LogFrames;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.host.HostSnapshot;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.json.JavalinGson;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What steward-ui is allowed to ask this container.
 *
 * <h2>Why this exists at all</h2>
 * §3 of the concept keeps the docker socket away from the web interface, and this is the other end
 * of that decision: the interface owns no socket, so everything it knows about a container - state,
 * health, image drift, the log, a console line - arrives through here. The trade is named rather
 * than hidden: whoever takes over steward-ui can call these endpoints, so what they can reach is
 * exactly this list and no more. Stopping, starting and recreating are <b>not</b> on it; those
 * happen by writing a row into {@code update_request}, which is countable, cancellable and carries
 * a countdown every player sees.
 *
 * <h2>The token is not optional</h2>
 * The service refuses to serve without one, for the same reason steward-deployer does: a console
 * that anybody on the network can type into is a remote shell with a nicer font. It is a shared
 * secret in the host's {@code .env}, given to both containers.
 */
public final class WorkerApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerApi.class);

    private final Docker docker;
    private final DockerOps ops;
    private final Console console;
    private final HostMetrics host;
    private final String project;
    private final Path backups;
    private final String token;

    /** Log follows are long and blocking; each one gets a thread of its own, and they are cheap. */
    private final ExecutorService followers = Executors.newVirtualThreadPerTaskExecutor();

    private Javalin app;

    public WorkerApi(final @NotNull Docker docker, final @NotNull DockerOps ops,
                     final @NotNull Console console, final @NotNull HostMetrics host,
                     final @NotNull String project, final @NotNull Path backups,
                     final @NotNull String token) {
        this.docker = docker;
        this.ops = ops;
        this.console = console;
        this.host = host;
        this.project = project;
        this.backups = backups;
        this.token = token;
    }

    public void start(final int port) {
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "api.token is empty. This API can type into a server console, so it does not "
                    + "serve without a shared secret; the setup script writes one into the host's "
                    + ".env and compose gives it to both containers.");
        }
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinGson(new Gson(), true));
            config.startup.showJavalinBanner = false;

            config.routes.before("/api/*", ctx -> {
                if (ctx.path().equals("/api/health")) {
                    return;
                }
                if (!token.equals(ctx.header("X-Steward-Token"))) {
                    throw new UnauthorizedResponse("bad or missing X-Steward-Token");
                }
            });

            config.routes.get("/api/health", ctx -> ctx.json(Map.of(
                    "status", "ok", "docker", docker.isReachable())));

            // Everything the start page's service table needs, in one request: §10c wants state,
            // health, image, uptime, RAM and CPU per service, and ten round trips for one table
            // would make the page slower than the thing it is describing.
            config.routes.get("/api/services", ctx -> ctx.json(services()));

            config.routes.get("/api/services/{name}", ctx -> ctx.json(
                    service(ctx.pathParam("name")).orElseThrow(
                            () -> new NotFoundResponse("no such service: " + ctx.pathParam("name")))));

            // The live log. SSE rather than a websocket: one direction, reconnects by itself, and
            // it passes through a reverse proxy without a special rule.
            config.routes.sse("/api/services/{name}/logs", client -> {
                final String name = client.ctx().pathParam("name");
                final String containerId = containerOf(name).orElse(null);
                if (containerId == null) {
                    client.sendEvent("gone", "no running container for " + name);
                    client.close();
                    return;
                }
                client.keepAlive();
                final boolean multiplexed = !docker.inspect(containerId).tty();
                final String tail = client.ctx().queryParamAsClass("tail", String.class)
                        .getOrDefault("200");
                final String since = client.ctx().queryParam("since");

                final DockerSocket.Stream stream = docker.logs(containerId, true, tail, since);
                client.onClose(() -> closeQuietly(stream, name));
                followers.submit(() -> {
                    try {
                        LogFrames.read(stream.body(), multiplexed, line -> client.sendEvent("line", line));
                    } catch (IOException e) {
                        log.debug("the log follow for {} ended", name, e);
                    } finally {
                        closeQuietly(stream, name);
                        client.close();
                    }
                });
            });

            // The second half of §10a's log search: what the browser has is filtered in the
            // browser, and this searches what Docker still holds - up to 50 MB per container,
            // measured on this host, and nothing older, because nothing older exists anywhere.
            config.routes.get("/api/services/{name}/logs/search", ctx -> {
                final String name = ctx.pathParam("name");
                final String pattern = ctx.queryParam("q");
                if (pattern == null || pattern.isBlank()) {
                    throw new BadRequestResponse("q is what to search for");
                }
                final String containerId = containerOf(name).orElseThrow(
                        () -> new NotFoundResponse("no running container for " + name));
                final boolean multiplexed = !docker.inspect(containerId).tty();
                final String since = ctx.queryParam("since");
                final int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(500);

                final List<String> found = new ArrayList<>();
                try (DockerSocket.Stream stream = docker.logs(containerId, false, "all", since)) {
                    LogFrames.read(stream.body(), multiplexed, line -> {
                        if (found.size() < limit && line.toLowerCase().contains(pattern.toLowerCase())) {
                            found.add(line);
                        }
                    });
                }
                ctx.json(Map.of("lines", found, "limit", limit, "truncated", found.size() >= limit));
            });

            // One line into one server's console. The answer is NOT in the response: `mc` hands the
            // line to tmux and the server prints its reply on its own console, which is the log
            // everybody is already watching. That is what makes a second admin's command visible
            // to the first instead of private.
            config.routes.post("/api/services/{name}/console", ctx -> {
                final ConsoleLine body = ctx.bodyAsClass(ConsoleLine.class);
                if (body == null || body.command == null || body.command.isBlank()) {
                    throw new BadRequestResponse("command is the line to type");
                }
                try {
                    console.send(ctx.pathParam("name"), body.command.strip());
                } catch (IllegalArgumentException e) {
                    throw new BadRequestResponse(e.getMessage());
                }
                ctx.status(202).json(Map.of("sent", body.command.strip(),
                        "where", "the answer appears in this service's log"));
            });

            config.routes.get("/api/host", ctx -> ctx.json(hostNumbers()));

            // What is actually on the disk, not what a run reported. A backup list read from the
            // report is a list of things somebody meant to write.
            config.routes.get("/api/backups", ctx -> ctx.json(archives()));
        }).start(port);

        log.info("the internal API is on {} - steward-ui reads the daemon through it", port);
    }

    private List<Map<String, Object>> services() {
        final ImageResult drift = ops.images();
        final List<Map<String, Object>> all = new ArrayList<>();
        for (final Docker.Container container : docker.containers(project)) {
            if (container.service() == null) {
                continue;
            }
            all.add(describe(container, drift));
        }
        all.sort((left, right) -> String.valueOf(left.get("service"))
                .compareTo(String.valueOf(right.get("service"))));
        return all;
    }

    private Map<String, Object> describe(final Docker.Container container, final ImageResult drift) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", container.service());
        row.put("containerId", container.id());
        row.put("image", container.image());
        row.put("state", container.state());
        row.put("status", container.status());
        row.put("hasConsole", Console.has(container.service()));
        row.put("drift", drift.state(container.service()).name());
        if (container.isRunning()) {
            try {
                final Docker.Inspection inspection = docker.inspect(container.id());
                row.put("health", inspection.health());
                row.put("startedAt", inspection.startedAt());
                final Docker.Stats stats = docker.stats(container.id());
                row.put("memoryBytes", stats.memoryBytes());
                row.put("memoryLimitBytes", stats.memoryLimitBytes());
                stats.cpuPercent().ifPresent(percent -> row.put("cpuPercent", percent));
            } catch (DockerException e) {
                // One container that will not answer is one row with less in it, never a page that
                // fails to draw.
                row.put("unreadable", e.getMessage());
            }
        }
        return row;
    }

    private Optional<Map<String, Object>> service(final String name) {
        final ImageResult drift = ops.images();
        return docker.containers(project).stream()
                .filter(container -> name.equals(container.service()))
                .findFirst()
                .map(container -> {
                    final Map<String, Object> row = describe(container, drift);
                    row.put("digests", docker.repoDigests(container.imageId()));
                    row.put("logLimit", "docker keeps up to 50 MB per container (5 x 10 MB) and "
                            + "nothing older; recreating the container starts that again");
                    return row;
                });
    }

    private Map<String, Object> hostNumbers() {
        final Map<String, Object> answer = new LinkedHashMap<>();
        try {
            final HostSnapshot snapshot = host.read();
            answer.put("load1", snapshot.load1());
            answer.put("cpus", snapshot.cpus());
            snapshot.cpuPercent().ifPresent(percent -> answer.put("cpuPercent", percent));
            answer.put("memoryTotalBytes", snapshot.memoryTotalBytes());
            answer.put("memoryAvailableBytes", snapshot.memoryAvailableBytes());
            answer.put("diskTotalBytes", snapshot.diskTotalBytes());
            answer.put("diskUsedBytes", snapshot.diskUsedBytes());
        } catch (IOException e) {
            answer.put("unreadable", "could not read /proc: " + e.getMessage());
        }
        try {
            final Docker.DiskUsage usage = docker.diskUsage();
            answer.put("imagesBytes", usage.imagesBytes());
            answer.put("volumesBytes", usage.volumesBytes());
        } catch (DockerException e) {
            answer.put("dockerDiskUnreadable", e.getMessage());
        }
        // No container in this stack sets a memory limit, so a percentage is a share of the whole
        // machine. The interface has to say that rather than print a number that looks like a
        // container's own budget (§10c).
        answer.put("containerLimits", "none - percentages are a share of the host");
        return answer;
    }

    private List<Map<String, Object>> archives() {
        final List<Map<String, Object>> all = new ArrayList<>();
        if (!Files.isDirectory(backups)) {
            return all;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backups)) {
            for (final Path entry : entries) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", entry.getFileName().toString());
                row.put("bytes", Files.size(entry));
                row.put("human", SnapshotResult.human(Files.size(entry)));
                row.put("modified", Files.getLastModifiedTime(entry).toInstant().toString());
                // A .partial is a backup that is either running right now or died halfway. Showing
                // it is the point: a directory that hides them looks tidy and is lying.
                row.put("partial", entry.getFileName().toString().endsWith(".partial"));
                all.add(row);
            }
        } catch (IOException e) {
            log.warn("could not list {}", backups, e);
        }
        all.sort((left, right) -> String.valueOf(right.get("modified"))
                .compareTo(String.valueOf(left.get("modified"))));
        return all;
    }

    private Optional<String> containerOf(final String service) {
        return docker.containers(project).stream()
                .filter(container -> service.equals(container.service()) && container.isRunning())
                .map(Docker.Container::id)
                .findFirst();
    }

    private static void closeQuietly(final DockerSocket.Stream stream, final String name) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("closing the log stream of {}", name, e);
        }
    }

    /** The body of a console POST. */
    private static final class ConsoleLine {
        private String command;
    }

    @Override
    public void close() {
        if (app != null) {
            app.stop();
        }
        followers.shutdownNow();
    }
}
