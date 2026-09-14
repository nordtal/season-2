package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import eu.nordtal.s2.steward.worker.backup.NightlyClock;
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
import io.javalin.http.sse.SseClient;
import io.javalin.json.JavalinGson;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * What {@code backup.at} says, and in which zone, so the interface can offer "tonight".
     *
     * @param at   {@code HH:mm} in this container's own time zone, or blank for no nightly backup
     * @param zone this container's zone - compose sets {@code TZ}, and it is not the browser's
     */
    public record Nightly(@NotNull String at, @NotNull ZoneId zone) { }

    private final Nightly nightly;

    /** Log follows are long and blocking; each one gets a thread of its own, and they are cheap. */
    private final ExecutorService followers = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Every log follow that is still open, so that shutting down can end them <em>first</em>.
     *
     * <h2>Why a set, rather than letting Jetty tidy up</h2>
     * A follow that was still open when Jetty stopped was not a tidy ending. Javalin then closes
     * the emitter against a request Jetty has already recycled, the close throws a
     * {@link NullPointerException}, and Javalin's own exception mapper throws a second one while
     * trying to read a header off that same dead request - so the failure cannot be reported and
     * is retried. Measured on this host on 2026-09-13: about sixty thousand of those a second,
     * for as long as the process lived. In a test JVM that is an {@code OutOfMemoryError} in the
     * build; on the host it is a container that will not go down and a disk filling with one
     * repeated line.
     *
     * <p>Closing the docker stream is what ends the read, which runs the follow's own
     * {@code finally} and closes the emitter while Jetty is still alive - the ordinary path,
     * taken deliberately instead of being raced into.</p>
     */
    private final Set<DockerSocket.Stream> follows = ConcurrentHashMap.newKeySet();

    /**
     * Set before anything is shut down, so a request already in flight can be told to give up.
     *
     * <p>Without it a follow could register its stream between {@link #close()} iterating
     * {@link #follows} and the executors refusing new work - and then be rejected by the scheduler
     * with nothing yet arranged to clean it up. The stream stayed open, the emitter stayed open,
     * and Jetty stopped on top of both, which is the shape the whole shutdown ordering exists to
     * prevent. The flag is set first and read twice: once before any work is done, and once after
     * the stream has been added, which is what makes the pair of them a handover rather than a
     * race - either close() sees the stream, or the follow sees the flag.</p>
     */
    private volatile boolean closing;

    /**
     * How often an open log follow says something, even when the container has not.
     *
     * <p><b>A connection nothing is written on is dropped after thirty seconds</b> - Jetty's own
     * idle timeout, measured on this host on 2026-09-13 - and Javalin's {@code keepAlive()} does
     * not write anything; it only holds the request open. A healthy Minecraft server is quiet for
     * minutes at a time, so the log view of one was closed under the watcher half a minute after
     * they opened it, and looked exactly like a server that had stopped talking.</p>
     *
     * <p>It matters a second time at the other end: {@code steward-ui} proxies this stream, and
     * when its browser goes away it cancels its side. The JDK's HTTP client only tears a
     * connection down when something next happens on it, so on a silent stream that cancellation
     * arrives nowhere and this process keeps a docker log stream open for a tab nobody has. A
     * comment every ten seconds is what lets both ends notice each other.</p>
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "steward-worker-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * How long a registry answer is good for.
     *
     * <p>A minute, because drift is caused by a push and not by a page refresh, and because the
     * answer is read by whoever is looking at the start page - which refreshes on a timer. The
     * response carries {@code driftCheckedAt} so the interface can say how old the comparison is
     * rather than implying it was made just now.</p>
     */
    private static final Duration DRIFT_TTL = Duration.ofMinutes(1);

    /**
     * One comparison and the moment it was made, as one value.
     *
     * <p>They were two fields, read one after the other: {@code services()} took the result and
     * {@code serviceTable()} then read the timestamp, with a TTL expiry possible in between. The
     * page could therefore draw a green tick from one comparison beside the words "compared a
     * minute ago" belonging to another - and this whole column exists because image drift went
     * unnoticed for four releases, so a row and its age have to be the same reading.</p>
     */
    private record Drift(@NotNull ImageResult result, @NotNull Instant checkedAt) { }

    /**
     * One thread, and it belongs to nobody's request.
     *
     * <p>A daemon, because a registry call in flight must not hold this process up on the way out,
     * and one rather than a pool because {@link Refreshed} never has two refreshes going at once.</p>
     */
    private final ExecutorService driftRefresh = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "steward-worker-drift");
        thread.setDaemon(true);
        return thread;
    });

    private final Refreshed<Drift> drift;

    private Javalin app;

    public WorkerApi(final @NotNull Docker docker, final @NotNull DockerOps ops,
                     final @NotNull Console console, final @NotNull HostMetrics host,
                     final @NotNull String project, final @NotNull Path backups,
                     final @NotNull String token, final @NotNull Nightly nightly) {
        this.docker = docker;
        this.ops = ops;
        this.console = console;
        this.host = host;
        this.project = project;
        this.backups = backups;
        this.token = token;
        this.nightly = nightly;
        // Here rather than at the field, because it reads `ops`, which is a constructor argument.
        this.drift = new Refreshed<>(() -> new Drift(ops.images(), Instant.now()), DRIFT_TTL,
                driftRefresh, Instant::now);
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
            config.routes.get("/api/services", ctx -> ctx.json(serviceTable()));

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
                follows.add(stream);
                if (closing) {
                    // close() may have walked `follows` a moment before this line put the stream in
                    // it. Nobody else will close it now, so this does.
                    goneOnShutdown(client, stream, name);
                    return;
                }
                final ScheduledFuture<?> heartbeat;
                final AtomicBoolean beating = new AtomicBoolean();
                try {
                    heartbeat = heartbeats.scheduleWithFixedDelay(
                            () -> beat(client, name, beating), HEARTBEAT.toSeconds(),
                            HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
                } catch (RejectedExecutionException rejected) {
                    goneOnShutdown(client, stream, name);
                    return;
                }
                client.onClose(() -> {
                    heartbeat.cancel(false);
                    closeQuietly(stream, name);
                });
                try {
                followers.submit(() -> {
                    try {
                        LogFrames.read(stream.body(), multiplexed, line -> {
                            // Asking before writing, rather than letting the write fail. Javalin
                            // does not throw on a terminated client - it logs "Cannot send data"
                            // and returns - so a follow whose browser has gone reads the container's
                            // whole backlog and reports every line of it to nobody, one warning per
                            // line. Measured on this host on 2026-09-13: a `tail=200` follow closed
                            // at its first line still wrote 69 of them.
                            if (client.terminated()) {
                                throw new Gone();
                            }
                            client.sendEvent("line", line);
                        });
                    } catch (Gone gone) {
                        log.debug("the follow of {} ended with whoever was watching it", name);
                    } catch (IOException e) {
                        log.debug("the log follow for {} ended", name, e);
                    } finally {
                        follows.remove(stream);
                        closeQuietly(stream, name);
                        client.close();
                    }
                });
                } catch (RejectedExecutionException rejected) {
                    heartbeat.cancel(false);
                    goneOnShutdown(client, stream, name);
                }
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
                if (limit <= 0) {
                    throw new BadRequestResponse("limit is how many matching lines to return at"
                            + " most, so it is at least 1; " + limit + " returns nothing and calls"
                            + " it a search that found nothing");
                }

                final Search search = new Search(pattern, limit);
                try (DockerSocket.Stream stream = docker.logs(containerId, false, "all", since)) {
                    LogFrames.read(stream.body(), multiplexed, search);
                }
                ctx.json(Map.of("lines", search.lines(), "limit", limit,
                        "truncated", search.truncated()));
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

            // The nightly clock, so that "tonight" in the interface means a moment on THIS host.
            // A browser works out four o'clock in its own time zone, which is not this container's
            // - and the whole point of the offer is to land before the backup rather than on it.
            config.routes.get("/api/schedule", ctx -> ctx.json(schedule()));

            // What is actually on the disk, not what a run reported. A backup list read from the
            // report is a list of things somebody meant to write.
            config.routes.get("/api/backups", ctx -> ctx.json(archives()));
        }).start(port);

        log.info("the internal API is on {} - steward-ui reads the daemon through it", port);

        // The registry call, once, before anybody asks. Refreshed only blocks when it has nothing
        // at all to hand over, and without this that one blocking read is the FIRST /api/services
        // after every start - 11.5s measured on this host, against steward-ui's ten-second
        // deadline. Getting it out of the way here costs nothing: nothing is waiting on this
        // thread, and a failure is the same one the background refresh already knows how to have.
        driftRefresh.execute(() -> {
            try {
                drift.get();
            } catch (RuntimeException failed) {
                log.warn("the first image comparison failed - the next request will try again: {}",
                        failed.toString());
            }
        });
    }

    /**
     * The service table, with the age of the drift comparison beside it.
     *
     * <p>An envelope rather than a bare array, because the interface has to be able to say
     * <em>"images compared a minute ago"</em>. A page that draws a green tick next to an answer
     * cached for an unknown length of time is making a promise it cannot keep - and image drift
     * going unnoticed for four releases is the failure this whole column exists to prevent.</p>
     */
    private Map<String, Object> serviceTable() {
        // Taken once, for the rows AND for the sentence about them.
        final Drift drift = drift();
        final List<Map<String, Object>> rows = services(drift.result());
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("services", rows);
        final ImageResult images = drift.result();
        final Map<String, Object> about = new LinkedHashMap<>();
        about.put("checkedAt", drift.checkedAt().toString());
        about.put("reached", images.reached());
        about.put("unverifiable", List.copyOf(images.unverifiable()));
        images.notCheckable().ifPresent(reason -> about.put("reason", reason));
        if (images.message() != null) {
            about.put("message", images.message());
        }
        answer.put("drift", about);
        return answer;
    }

    /**
     * The rows themselves, and the two measurements that made them worth writing carefully.
     *
     * <p>On this host on 2026-09-13: {@code /containers/{id}/stats?stream=false} takes <b>1.03 s</b>
     * per container, because the daemon collects two samples to compute a CPU delta and there is no
     * {@code one-shot} that still yields a real percentage. Nine running containers read one after
     * another is a nine-second request - slower than most of what it is describing. So the rows are
     * read in parallel, one virtual thread each, and the table costs about as long as its slowest
     * row.</p>
     *
     * <p>The drift answer is cached for {@link #DRIFT_TTL}. It asks a registry over the network,
     * and image drift changes when somebody pushes - not between two refreshes of a page. Without
     * the cache the start page would send a burst of registry requests every few seconds for an
     * answer that is the same all day.</p>
     */
    private List<Map<String, Object>> services(final ImageResult drift) {
        final List<Docker.Container> containers = docker.containers(project).stream()
                .filter(container -> container.service() != null)
                .toList();
        final List<Map<String, Object>> all;
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            all = scope.invokeAll(containers.stream()
                            .map(container -> (java.util.concurrent.Callable<Map<String, Object>>)
                                    () -> describe(container, drift))
                            .toList()).stream()
                    .map(WorkerApi::resultOf)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading the service table was interrupted", e);
        }
        return all.stream()
                .sorted((left, right) -> String.valueOf(left.get("service"))
                        .compareTo(String.valueOf(right.get("service"))))
                .toList();
    }

    private static Map<String, Object> resultOf(final java.util.concurrent.Future<Map<String, Object>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading one service was interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            // describe() already swallows a Docker failure into an `unreadable` field, so anything
            // arriving here is a programming error and belongs in the log rather than in a row.
            throw new IllegalStateException("reading one service failed", e.getCause());
        }
    }

    /**
     * The drift answer as it stands, which is not necessarily the newest one there could be.
     *
     * <p>See {@link Refreshed} for why this no longer reads the registry on the caller's thread.
     * The short of it: it used to, and steward-ui's ten-second deadline ran out on one request in
     * every sixty while the interface logged that a healthy service could not be reached.</p>
     */
    private Drift drift() {
        return drift.get();
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
        final ImageResult drift = drift().result();
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

    /** {@code backup.at}, the zone it is read in, and the next moment it comes round. */
    private Map<String, Object> schedule() {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("backupAt", nightly.at().isBlank() ? null : nightly.at());
        answer.put("zone", nightly.zone().getId());
        answer.put("nextBackupAt", NightlyClock.next(nightly.at(), nightly.zone(),
                        ZonedDateTime.now(nightly.zone()))
                .map(next -> next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .orElse(null));
        return answer;
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
        // container's own budget (§10c). A whole sentence, because the interface prints it as one:
        // "none - percentages are a share of the host" needs a label in front of it to parse, and
        // the label the status page used to carry said the same thing a second time.
        answer.put("containerLimits",
                "No container sets a memory limit, so every percentage here is a share of the whole host.");
        return answer;
    }

    private List<Map<String, Object>> archives() {
        final List<Map<String, Object>> all = new ArrayList<>();
        if (!Files.isDirectory(backups)) {
            return all;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backups)) {
            for (final Path entry : entries) {
                archiveRow(entry).ifPresent(all::add);
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

    /**
     * One heartbeat, written somewhere it is allowed to block.
     *
     * <h2>What was actually measured, and what was not</h2>
     * The reason this method exists was reported as: {@link #heartbeats} is a single thread, so a
     * consumer that has stopped reading parks the write and takes every other follow's heartbeat
     * with it. <b>That does not reproduce on this stack</b>, and the measurements are written down
     * in {@code HeartbeatLeavesTheTimerTest} rather than repeated here. Two things in other
     * people's code are why - Javalin's comment path is not synchronised, and Jetty buffers - so
     * the hazard is latent rather than live, and it is latent on two facts nobody would be told
     * had changed.
     *
     * <p>What the handover does demonstrably fix is smaller and real: an unchecked exception out of
     * {@code sendComment} used to escape the {@code Runnable}, and
     * {@code ScheduledThreadPoolExecutor} then cancels that periodic task <em>permanently</em> -
     * the follow's heartbeat never returns and Jetty drops it thirty seconds later. The
     * {@code catch} below is what prevents that.</p>
     *
     * <p>{@code beating} keeps the handover from becoming a queue of writes nobody is reading:
     * while one comment is still on its way out, the next tick is skipped. A consumer that misses
     * heartbeats because it is not reading is one Jetty is about to close, which is the outcome
     * that was wanted.</p>
     */
    private void beat(final SseClient client, final String name, final AtomicBoolean beating) {
        if (!beating.compareAndSet(false, true)) {
            return;
        }
        try {
            followers.submit(() -> {
                try {
                    client.sendComment("following " + name);
                } catch (RuntimeException e) {
                    log.debug("the heartbeat for {} could not be written", name, e);
                } finally {
                    beating.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            // close() got there first. The follow is being torn down anyway.
            beating.set(false);
        }
    }

    /**
     * Ends a follow that arrived while this process was going away, leaving nothing open.
     *
     * <p>Both callers are races with {@link #close()}, and the reader deserves a sentence rather
     * than a connection that simply stops: an SSE client reconnects by itself, and "the server is
     * going away" is what tells the page to say so instead of retrying into a closed port.</p>
     */
    private void goneOnShutdown(final SseClient client, final DockerSocket.Stream stream,
                                final String name) {
        follows.remove(stream);
        closeQuietly(stream, name);
        client.sendEvent("gone", "steward-worker is shutting down");
        client.close();
    }

    /**
     * Nobody is reading this any more, thrown from inside the line consumer to get out of the read.
     *
     * <p>It carries no stack trace: it is not a failure, it is the ordinary end of a follow, and it
     * happens once per closed tab.</p>
     */
    private static final class Gone extends RuntimeException {

        Gone() {
            super(null, null, false, false);
        }
    }

    private static void closeQuietly(final DockerSocket.Stream stream, final String name) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("closing the log stream of {}", name, e);
        }
    }

    /**
     * One row of the archive list, or nothing if that entry is not a file any more.
     *
     * <h2>One stat, not four</h2>
     * This used to ask the filesystem five separate questions about one path - {@code isRegularFile},
     * {@code size} twice, {@code getLastModifiedTime} - and a backup directory is the one place
     * where the answers genuinely change between them. The writer grows the {@code .partial} while
     * this runs and renames it over the finished name when it is done, so the old code could report
     * {@code bytes} from one moment and {@code human} from another: a row reading
     * "8 294 001 bytes (7.6 MB)" where the two halves disagree. Read once, report that one moment.
     *
     * <h2>An entry that vanished loses its row, not the listing</h2>
     * The same rename makes a path from the directory stream disappear before it can be read, and
     * {@code Files.size} on it throws. Thrown out of the loop, that turned "one archive finished
     * while you were looking" into an empty backup page - the screen an admin reads as "the backups
     * are gone". It is the most ordinary moment there is in that directory, so it ends the entry and
     * nothing more.
     */
    static Optional<Map<String, Object>> archiveRow(final Path entry) {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(entry, BasicFileAttributes.class);
        } catch (IOException gone) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            return Optional.empty();
        }
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", entry.getFileName().toString());
        row.put("bytes", attributes.size());
        row.put("human", SnapshotResult.human(attributes.size()));
        row.put("modified", attributes.lastModifiedTime().toInstant().toString());
        // A .partial is a backup that is either running right now or died halfway. Showing
        // it is the point: a directory that hides them looks tidy and is lying.
        row.put("partial", entry.getFileName().toString().endsWith(".partial"));
        return Optional.of(row);
    }

    /** The body of a console POST. */
    private static final class ConsoleLine {
        private String command;
    }

    /**
     * One log search: the matching lines up to the limit, and whether the limit hid any.
     *
     * <p><b>Exactly the limit is not truncation, and saying it is costs the reader the search.</b>
     * The rule used to be {@code found.size() >= limit}: a search for a word that appears five
     * times, asked for five lines, answered all five and then said it had stopped early. The
     * honest reading of that is "there is more, narrow it down" - so an admin looking for the
     * stack trace that matters narrows a search that was already complete, and the line they were
     * looking for is now excluded by the term they added. This counts every match and only calls
     * the answer truncated when one of them was left out.</p>
     *
     * <p>Counting past the limit is free here: the stream is read to the end either way, because
     * the frames come from one socket that has to be drained before it can be closed.</p>
     */
    static final class Search implements java.util.function.Consumer<String> {

        private final String needle;
        private final int limit;
        private final List<String> lines = new ArrayList<>();
        private int matched;

        Search(final String pattern, final int limit) {
            this.needle = pattern.toLowerCase(java.util.Locale.ROOT);
            this.limit = limit;
        }

        @Override
        public void accept(final String line) {
            if (!line.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                return;
            }
            matched++;
            if (lines.size() < limit) {
                lines.add(line);
            }
        }

        List<String> lines() {
            return List.copyOf(lines);
        }

        /** True only when a matching line was left out, never merely because the list is full. */
        boolean truncated() {
            return matched > limit;
        }
    }

    /**
     * Stops, and the order of these five lines is the whole of it.
     *
     * <p>Every open follow is ended before Jetty is - see {@link #follows} for what happens when
     * it is the other way round. Closing the stream is what unblocks the read; the follow's own
     * {@code finally} then closes the emitter, which is why this waits for those threads rather
     * than assuming they got there. Two seconds is far longer than an interrupted read needs and
     * short enough that nobody watches a container refuse to stop.</p>
     */
    @Override
    public void close() {
        // FIRST, and before anything is shut down: a request that is halfway through arranging a
        // follow reads this and cleans up after itself instead of being refused by an executor that
        // is already gone.
        closing = true;
        heartbeats.shutdownNow();
        // Not awaited: a registry call has its own timeout and nothing is waiting for its answer.
        // Refreshed handles the rejection that a later reader will get from this.
        driftRefresh.shutdownNow();
        for (final DockerSocket.Stream stream : follows) {
            closeQuietly(stream, "a follow still open at shutdown");
        }
        followers.shutdownNow();
        try {
            if (!followers.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("a log follow was still running two seconds into shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (app != null) {
            app.stop();
        }
    }
}
