package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.online.OnlinePlayer;
import eu.nordtal.s2.common.update.ServiceHold;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.backup.NightlyClock;
import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.backup.TarSnapshots;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.docker.LogFrames;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.host.HostSnapshot;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.plan.Change;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.sse.SseClient;
import io.javalin.json.JavalinGson;
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
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * What {@code backup.at} and {@code update.at} say, and in which zone, so the interface can
     * offer "tonight" and show when either clock fires next.
     *
     * @param at       {@code HH:mm} in this container's own time zone, or blank for no nightly backup
     * @param updateAt the same for the scheduled update, blank - the default - for none
     * @param zone     this container's zone - compose sets {@code TZ}, and it is not the browser's
     */
    public record Nightly(
            @NotNull String at,
            @NotNull List<String> days,
            @NotNull String updateAt,
            @NotNull List<String> updateDays,
            @NotNull ZoneId zone) {

        /** No scheduled update, which is what a config without the {@code update} section says. */
        public Nightly(final @NotNull String at, final @NotNull List<String> days, final @NotNull ZoneId zone) {
            this(at, days, "", List.of(), zone);
        }
    }

    /** Asked on every request, because a save of steward.yml changes it without a restart. */
    private final Supplier<Nightly> nightly;

    /**
     * The plugin list, the Modrinth search, and the two buttons (season-2-ops/129).
     *
     * <p>Null in a deployment with no database, because the added plugins are a table. Every route
     * of it then answers 503 rather than an empty list - see the constructor.</p>
     */
    private final @org.jetbrains.annotations.Nullable PluginsApi managedPlugins;

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

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
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
    /** The console's steps are 1000, 5000 and 10000 lines; counting past the top one buys nothing. */
    static final int LOG_CAPACITY_MAX = 10_000;

    private static final Duration LOG_CAPACITY_TTL = Duration.ofMinutes(5);

    /**
     * One comparison and the moment it was made, as one value.
     *
     * <p>They were two fields, read one after the other: {@code services()} took the result and
     * {@code serviceTable()} then read the timestamp, with a TTL expiry possible in between. The
     * page could therefore draw a green tick from one comparison beside the words "compared a
     * minute ago" belonging to another - and this whole column exists because image drift went
     * unnoticed for four releases, so a row and its age have to be the same reading.</p>
     */
    private record Drift(
            @NotNull ImageResult result, @NotNull Instant checkedAt) {}

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

    /**
     * How long a resolve is good for, and why this is hours rather than the minute drift gets.
     *
     * <h2>What it answers, and what it still must not do</h2>
     * season-2-ops/128. {@code UpdateServer} opens with <i>"the first rule of this module is that
     * nothing updates on a schedule"</i>, and that rule is untouched here: <b>looking is not
     * running.</b> This asks Modrinth, GitHub and the Fill API what is newest and compares it with
     * the jars in the volumes - the same {@code Runs#resolve} a run starts with, which writes
     * nothing, anywhere. No row is written into {@code update_request} and no container is touched.
     * A run is still only ever a row somebody asked for.
     *
     * <h2>Why a cache and not a clock</h2>
     * A timer would ask on a schedule whether or not anybody wanted to know, which is the shape
     * that gets a token rate-limited for nothing. {@link Refreshed} asks when the page is opened
     * and hands the previous answer over while a new one is fetched behind it, so ten admins
     * looking at once cost one round of API calls and nobody waits on the network. A week in which
     * nobody opens the page is a week in which nothing is asked - which is correct, because there
     * was nobody to show it to.
     *
     * <p>Six hours, because a plugin release is a thing that happens a few times a month and the
     * answer carries {@code checkedAt} beside it. The page says how old the reading is rather than
     * implying it was taken just now, exactly as the drift column does one field up.</p>
     */
    private static final Duration AVAILABLE_TTL = Duration.ofHours(6);

    /** One resolve and the moment it was made, for the same reason {@link Drift} is one value. */
    private record Available(
            @NotNull UpdatePlan plan, @NotNull Instant checkedAt) {}

    /**
     * The resolve, or {@code null} where this API has no sources to ask - every test that builds a
     * {@link WorkerApi} without one, and any deployment where the wiring chose not to.
     *
     * <p>Null rather than a supplier returning an empty plan, because the two are different
     * answers: an empty plan says "everything is current" and there is nothing behind it to say
     * that. The endpoint answers 503 instead, which the page can draw as "could not look" - the
     * distinction {@link Change.Status#UNRESOLVED} exists for, one level up.</p>
     */
    private final @org.jetbrains.annotations.Nullable Refreshed<Available> available;

    private Javalin app;

    private final ConfigApi configs;
    private final MessagesApi messages;
    private final ActionsApi actions;
    /** The Disk field of one service's page; never part of the service table. */
    private final DiskUsage disk;
    /** The runs before the container, out of the server's own rotated logs. */
    private final LogArchive archive;
    /** How many lines the console can fill per service, Docker plus archive, capped at the top step. */
    private final Map<String, Refreshed<Integer>> logCapacity = new ConcurrentHashMap<>();

    /**
     * The player counts, or {@code null} on a deployment that has no database to read them from.
     *
     * <p>Nullable and not an empty {@link ServicesApi}, because the two are different states worth
     * keeping apart at the wiring: no directory at all is a test or a stack without Postgres, and
     * an empty answer is proxy not having written recently. Both leave the field off a
     * row - see {@link #describe} - which is the whole point (steward/86).
     */
    private final @org.jetbrains.annotations.Nullable ServicesApi players;

    /**
     * {@code update_request} and {@code service_hold}, read for one thing only (season-2-ops/125).
     *
     * <h2>This is the exception to "never the database" above, and it is the only one</h2>
     * A service that is down because somebody pressed Down and a service that is down because it
     * fell over are the same container to Docker: stopped, with an exit code. The difference is a
     * decision a person made, it exists in exactly one place, and the table this API draws is the
     * place it has to show up. There is no reading of the runtime that could replace it.
     *
     * <p>It costs one small indexed query per {@code /api/services}, taken once for the whole table
     * rather than once per row, for the same reason the player counts are.</p>
     */
    private final @NotNull UpdateDirectory updates;

    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Nightly nightly) {
        this(docker, ops, console, host, project, backups, token, configs, null, updates, audit, nightly);
    }

    /**
     * @param volumesRoot where the four Minecraft volumes are mounted - see
     *                    {@code StewardSpec#volumesRoot}. It is only ever read for one thing: finding
     *                    the jar a standalone module's message bundle lives in, since that jar (unlike
     *                    a Paper or Velocity plugin's) is not under {@code configs}. {@code null}
     *                    skips that second lookup rather than failing - a bundle whose jar cannot be
     *                    found is left off the list (see {@code MessageBundles#discover}), not this
     *                    process refusing to start over a mount most tests do not need.
     * @param updates     {@code update_request}, already opened over this process's own pool - see
     *                    {@code StewardWorker#serve} for why one directory is shared between this API
     *                    and the run loop rather than two directories over the same table
     * @param audit       {@code audit_log}, opened the same way. Both feed {@link ActionsApi}; the
     *                    update directory is read once more, for the holds - see {@link #updates}
     *                    for why that one reading is worth the exception to "this class talks to
     *                    Docker and the filesystem, never the database" (§3)
     */
    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @org.jetbrains.annotations.Nullable Path volumesRoot,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Nightly nightly) {
        this(docker, ops, console, host, project, backups, token, configs, volumesRoot, updates, audit, nightly, null);
    }

    /**
     * @param online where the player counts and the player list come from, or {@code null} for a
     *               deployment with no database behind this API. A {@link ServicesApi} and not the
     *               two directories behind it: what it reads (two tables today) is its business,
     *               and what this class needs is one answer per response. See {@link ServicesApi}
     *               for why a subject it cannot vouch for is left out of the answer rather than
     *               sent as {@code 0} or an empty list (steward/86, steward/111).
     */
    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @org.jetbrains.annotations.Nullable Path volumesRoot,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Nightly nightly,
            final @org.jetbrains.annotations.Nullable ServicesApi online) {
        this(
                docker,
                ops,
                console,
                host,
                project,
                backups,
                token,
                configs,
                volumesRoot,
                updates,
                audit,
                nightly,
                online,
                null);
    }

    /**
     * @param resolve what is newest, asked of Modrinth, GitHub and the Fill API and compared with
     *                the jars in the volumes - {@code Runs#resolve}, which writes nothing anywhere.
     *                {@code null} leaves {@code GET /api/updates/available} answering 503 rather
     *                than an empty plan; see {@link #available} for why those are not the same
     *                answer. It is a supplier and not a resolved plan because this API must never
     *                hold one from process start - a jar installed an hour ago has to stop being
     *                reported as available, and the only way it does is by asking again.
     */
    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @org.jetbrains.annotations.Nullable Path volumesRoot,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Nightly nightly,
            final @org.jetbrains.annotations.Nullable ServicesApi online,
            final @org.jetbrains.annotations.Nullable Supplier<UpdatePlan> resolve) {
        this(
                docker,
                ops,
                console,
                host,
                project,
                backups,
                token,
                configs,
                volumesRoot,
                updates,
                audit,
                nightly,
                online,
                resolve,
                null,
                null);
    }

    /**
     * @param managedPlugins the four routes behind "the plugins on this server"
     *                       (season-2-ops/129), or {@code null} in a deployment with no database -
     *                       they then answer 503, for the reason {@link #available} gives: an empty
     *                       plugin list and a worker that cannot read the table are different
     *                       answers, and guessing the friendlier one would be a lie about what is
     *                       installed
     */
    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @org.jetbrains.annotations.Nullable Path volumesRoot,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Nightly nightly,
            final @org.jetbrains.annotations.Nullable ServicesApi online,
            final @org.jetbrains.annotations.Nullable Supplier<UpdatePlan> resolve,
            final @org.jetbrains.annotations.Nullable PluginsApi managedPlugins) {
        this(
                docker,
                ops,
                console,
                host,
                project,
                backups,
                token,
                configs,
                volumesRoot,
                updates,
                audit,
                nightly,
                online,
                resolve,
                managedPlugins,
                null);
    }

    /**
     * @param accessInbox the bot's request inbox (season-2-community/08), or {@code null} in a
     *                    deployment with no database - saving the bot's messages then answers
     *                    that a restart is needed, which is what is true there
     */
    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @org.jetbrains.annotations.Nullable Path volumesRoot,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Nightly nightly,
            final @org.jetbrains.annotations.Nullable ServicesApi online,
            final @org.jetbrains.annotations.Nullable Supplier<UpdatePlan> resolve,
            final @org.jetbrains.annotations.Nullable PluginsApi managedPlugins,
            final @org.jetbrains.annotations.Nullable eu.nordtal.s2.common.access.AccessRequests accessInbox) {
        this(
                docker,
                ops,
                console,
                host,
                project,
                backups,
                token,
                configs,
                volumesRoot,
                updates,
                audit,
                () -> nightly,
                online,
                resolve,
                managedPlugins,
                accessInbox,
                () -> {});
    }

    /**
     * @param nightly   the schedule as it stands right now - a supplier, because it changes when
     *                  steward.yml is saved
     * @param reReadOwn what a save of this worker's own {@code steward.yml} runs once the file is
     *                  written: re-read it and re-arm the clocks. It may throw; the save has
     *                  already happened, and the answer then says the change waits for a restart.
     */
    public WorkerApi(
            final @NotNull Docker docker,
            final @NotNull DockerOps ops,
            final @NotNull Console console,
            final @NotNull HostMetrics host,
            final @NotNull String project,
            final @NotNull Path backups,
            final @NotNull String token,
            final @NotNull Path configs,
            final @org.jetbrains.annotations.Nullable Path volumesRoot,
            final @NotNull UpdateDirectory updates,
            final @NotNull AuditDirectory audit,
            final @NotNull Supplier<Nightly> nightly,
            final @org.jetbrains.annotations.Nullable ServicesApi online,
            final @org.jetbrains.annotations.Nullable Supplier<UpdatePlan> resolve,
            final @org.jetbrains.annotations.Nullable PluginsApi managedPlugins,
            final @org.jetbrains.annotations.Nullable eu.nordtal.s2.common.access.AccessRequests accessInbox,
            final @NotNull Runnable reReadOwn) {
        this.managedPlugins = managedPlugins;
        this.players = online;
        this.updates = updates;
        this.docker = docker;
        this.ops = ops;
        this.console = console;
        this.host = host;
        this.project = project;
        this.backups = backups;
        this.token = token;
        this.nightly = nightly;
        // The configuration editor's whole back end. It lives here and not in steward-ui because
        // every file it touches is 0600 root:root and steward-ui is the one service that is not
        // root - see ApiSpec#configsRoot for the measurement that moved it.
        this.configs = new ConfigApi(configs, console::send, java.util.Map.of(ConfigApi.OWN_CONFIG, reReadOwn));
        // A message bundle is not a config file - see MessagesApi's own javadoc for why it is kept
        // apart rather than folded into ConfigApi (steward/48).
        this.messages = new MessagesApi(configs, volumesRoot, accessInbox, console::send);
        // The unified "latest actions" feed (steward/82) - see ActionsApi's own javadoc for why one
        // query over two tables and not a merge on the frontend's side.
        this.actions = new ActionsApi(updates, audit);
        // Its own virtual thread per refresh, not driftRefresh: a du queued behind a registry
        // comparison over the internet would age the number for no reason of its own.
        this.disk = new DiskUsage(
                volumesRoot, runnable -> Thread.ofVirtual().name("disk-usage").start(runnable));
        this.archive = new LogArchive(volumesRoot);
        // Here rather than at the field, because it reads `ops`, which is a constructor argument.
        this.drift =
                new Refreshed<>(() -> new Drift(ops.images(), Instant.now()), DRIFT_TTL, driftRefresh, Instant::now);
        // The same background thread as drift, and deliberately so: both are slow calls over the
        // internet made on nobody's request, Refreshed never has two of its own going at once, and
        // a resolve waiting behind a registry comparison costs a page that is already showing the
        // previous answer nothing at all.
        this.available = resolve == null
                ? null
                : new Refreshed<>(
                        () -> new Available(resolve.get(), Instant.now()), AVAILABLE_TTL, driftRefresh, Instant::now);
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

                    config.routes.get(
                            "/api/health", ctx -> ctx.json(Map.of("status", "ok", "docker", docker.isReachable())));

                    // Everything the start page's service table needs, in one request: §10c wants state,
                    // health, image, uptime, RAM and CPU per service, and ten round trips for one table
                    // would make the page slower than the thing it is describing.
                    config.routes.get("/api/services", ctx -> ctx.json(serviceTable()));

                    config.routes.get(
                            "/api/services/{name}",
                            ctx -> ctx.json(service(ctx.pathParam("name"))
                                    .orElseThrow(
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
                        final String tail = client.ctx()
                                .queryParamAsClass("tail", String.class)
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
                                    () -> beat(client, name, beating),
                                    HEARTBEAT.toSeconds(),
                                    HEARTBEAT.toSeconds(),
                                    TimeUnit.SECONDS);
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
                                    backlog(client, containerId, name, tail, multiplexed);
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
                        ctx.status(202)
                                .json(Map.of(
                                        "sent",
                                        body.command.strip(),
                                        "where",
                                        "the answer appears in this service's log"));
                    });

                    // The configuration of every service in the stack (§10a.6). steward-ui proxies these
                    // three verbatim: it draws the form and holds the security key in front of it, and
                    // this side holds the file permissions. Neither half can do the other's job, which is
                    // the point.
                    config.routes.get("/api/config", configs::list);
                    config.routes.get("/api/config/<file>", configs::one);
                    config.routes.put("/api/config/<file>", configs::save);

                    // The raw editor's own save (steward/60) - a route of its own rather than a fourth
                    // verb on the three above, because a raw save carries text and a revision, never a
                    // `changes` map, and nothing here refuses on what that text says. See ConfigApi#saveRaw.
                    config.routes.put("/api/config-raw/<file>", configs::saveRaw);

                    // The message bundles (steward/48) - their own routes and their own card in the
                    // interface, never folded into the three above. See MessagesApi's javadoc for why.
                    config.routes.get("/api/messages", messages::list);
                    config.routes.get("/api/messages/<bundle>", messages::one);
                    config.routes.put("/api/messages/<bundle>", messages::save);

                    config.routes.get("/api/host", ctx -> ctx.json(hostNumbers()));

                    // The nightly clock, so that "tonight" in the interface means a moment on THIS host.
                    // A browser works out four o'clock in its own time zone, which is not this container's
                    // - and the whole point of the offer is to land before the backup rather than on it.
                    config.routes.get("/api/schedule", ctx -> ctx.json(schedule()));

                    // What is actually on the disk, not what a run reported. A backup list read from the
                    // report is a list of things somebody meant to write.
                    config.routes.get("/api/backups", ctx -> ctx.json(archives()));

                    // steward/95's download: one archive, streamed rather than read into memory - these are
                    // hundreds of megabytes. `{name}` is a single path segment, so a literal `/` in it is
                    // already refused by the router before this ever runs; downloadBackup itself does not
                    // rely on that alone. See its own javadoc for the two checks it does make.
                    config.routes.get(
                            "/api/backups/{name}/download", ctx -> downloadBackup(ctx, ctx.pathParam("name")));

                    // steward-ui's push watch (steward/98): a small derived reading, not the service table
                    // again - see AlertLevel's own javadoc for why it is a subset of health.ts's summarise
                    // and reads the same maps this class already built rather than a copy of their shape.
                    config.routes.get("/api/alert-level", ctx -> ctx.json(alertLevel()));

                    // The unified "latest actions" feed (steward/82) - the newest few rows across
                    // update_request and audit_log, merged and sorted here rather than by the interface.
                    // See ActionsApi's own javadoc for why it is one query and not two.
                    config.routes.get("/api/actions", actions::list);

                    // season-2-ops/129: the plugins on one Minecraft server, and the Modrinth search
                    // beside them. The list is read off the disk and the table only says which rows may
                    // be deleted - see PluginsApi for why that is the enforcement of "the Nordtal plugins
                    // are fixed" rather than a greyed-out button.
                    //
                    // Installing is a row and not an install: the jar arrives with the next update run,
                    // through the ordinary resolve, because Topology.servicesWith merges the table into
                    // the fixed list. Removing is the one thing here that touches the disk at once, and it
                    // deletes the data folder as well - which is why the list hands its name over first.
                    config.routes.get(
                            "/api/services/{name}/plugins", ctx -> plugins().list(ctx));
                    config.routes.get(
                            "/api/services/{name}/plugins/search",
                            ctx -> plugins().search(ctx));
                    config.routes.post(
                            "/api/services/{name}/plugins", ctx -> plugins().add(ctx));
                    config.routes.delete(
                            "/api/services/{name}/plugins/{artifact}",
                            ctx -> plugins().remove(ctx));

                    // season-2-ops/128: WHAT A RUN WOULD DO, WITHOUT DOING IT. Until this existed the only
                    // way to see whether PacketEvents or Paper had moved was to start a run, so the plan page
                    // said in as many words that there was no dry run and drew an image comparison
                    // instead. Reading this route writes nothing: no row in update_request, no container
                    // touched, no jar moved. See AVAILABLE_TTL for why that is not a breach of "nothing
                    // updates on a schedule" but the other half of it.
                    config.routes.get("/api/updates/available", ctx -> {
                        if (available == null) {
                            // 503 and not an empty plan. See the field for why the two are different
                            // answers and why guessing the friendlier one would be a lie.
                            ctx.status(503)
                                    .json(Map.of(
                                            "error",
                                            "this worker has no sources configured, so nothing can be resolved"));
                            return;
                        }
                        // season-2-ops/142: the same reading, asked for again on purpose. The cache holds
                        // six hours, which is right for a page somebody opens and wrong for the one moment
                        // they have just published something and want to see it - and without this that
                        // wait is one nobody can shorten.
                        //
                        // A parameter on the read rather than a POST of its own, and that is the honest
                        // shape: it costs a lot and still changes nothing. Steward's own rule is that a
                        // writing route needs the security key touched in the last five minutes
                        // (`GateTest`), and asking a refusable question about it every time somebody wants
                        // a current answer would be a key ceremony for a refresh button. What it does to
                        // the cache is throw it away, which is what any cache-busting read does.
                        if (ctx.queryParam("refresh") != null) {
                            available.invalidate();
                        }
                        ctx.json(availability(available.get()));
                    });
                })
                .start(port);

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
                log.warn("the first image comparison failed - the next request will try again: {}", failed.toString());
            }
        });
    }

    /**
     * The plugin routes, or a refusal that says why there are none.
     *
     * <p>503 and not an empty list, for the same reason {@code /api/updates/available} answers 503:
     * "no plugins" and "this worker cannot read the table" are different sentences, and a page that
     * draws the friendlier one is a page claiming a server runs nothing.</p>
     */
    private PluginsApi plugins() {
        if (managedPlugins == null) {
            throw new io.javalin.http.ServiceUnavailableResponse(
                    "this worker has no database, so it cannot say which plugins were added");
        }
        return managedPlugins;
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
        // Once for the whole table, not once per row: it is a single read of four rows, and four
        // reads of it would also let two rows of one answer disagree about the same instant.
        final ServicesApi.Online counts = online();
        // Once for the whole table, for the same reason: two rows of one answer must not disagree
        // about which services are being held.
        final Map<String, ServiceHold> holds = new LinkedHashMap<>();
        for (final ServiceHold hold : updates.holds()) {
            holds.put(hold.service(), hold);
        }
        final List<Map<String, Object>> all;
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            all = scope
                    .invokeAll(containers.stream()
                            .map(container -> (java.util.concurrent.Callable<Map<String, Object>>)
                                    () -> describe(container, drift, counts, holds))
                            .toList())
                    .stream()
                    .map(WorkerApi::resultOf)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading the service table was interrupted", e);
        }
        return all.stream()
                .sorted((left, right) ->
                        String.valueOf(left.get("service")).compareTo(String.valueOf(right.get("service"))))
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

    /**
     * One row.
     *
     * <p>{@code players} is written only for a service {@code counts} actually names. A service it
     * does not name has NO {@code players} key at all - not {@code 0} and not {@code null} - because
     * "nobody is connected" and "the proxy has not said" are different answers and a dashboard
     * that draws the second as the first is the failure {@code ImageResult.State.UNKNOWN} already
     * exists to prevent. See {@link ServicesApi} (steward/86).
     *
     * <p>{@code roster} follows the same rule one step further (steward/111): it appears only for a
     * service that has fresh players, it is never an empty array, and it is never sent for a service
     * nobody is on. It enriches {@code players} and never contradicts it - both come out of one
     * {@link ServicesApi#read()} taken once for the whole response, so no two rows of one answer can
     * disagree about the same instant.
     */
    private Map<String, Object> describe(
            final Docker.Container container,
            final ImageResult drift,
            final ServicesApi.Online counts,
            final Map<String, ServiceHold> holds) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", container.service());
        row.put("containerId", container.id());
        row.put("image", container.image());
        row.put("state", container.state());
        row.put("status", container.status());
        row.put("hasConsole", Console.has(container.service()));
        row.put("drift", drift.state(container.service()).name());
        putOnline(row, container.service(), counts);
        putStandby(row, container.service());
        // Same rule as `players`: the key is absent for a service nobody is holding, rather than
        // present and false. "Not held" and "held by nobody in particular" are different answers.
        final ServiceHold hold = holds.get(container.service());
        if (hold != null) {
            final Map<String, Object> about = new LinkedHashMap<>();
            about.put("since", hold.since().toString());
            about.put("by", hold.heldBy());
            row.put("hold", about);
        }
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

    /** The counts and the list as they stand, or nothing at all - never a guessed zero. */
    private ServicesApi.Online online() {
        return players == null ? ServicesApi.Online.NONE : players.read();
    }

    /**
     * Marks the row of a service whose normal state is <em>stopped</em>.
     *
     * <p><b>A standby is not down, it is off</b> (steward/125). {@code proxy-standby} and
     * {@code limbo-standby} live in the {@code standby} compose profile and are stopped for all but
     * a minute of the season, so a dashboard that reads "not running" as a fault reports two faults
     * on a perfectly healthy stack - every day, which is precisely how a fault counter stops being
     * read and how the third fault goes unnoticed.</p>
     *
     * <p>It has to be said <em>here</em> because it cannot be seen anywhere else: to Docker a
     * stopped standby and a crashed backend are the same container state, and the frontend has
     * nothing but the name to go on. {@link Topology#standbyNames()} is the only thing that knows,
     * and this is the one place it is asked.</p>
     *
     * <p>Same rule as {@code players} and {@code hold}: the key is <em>absent</em> for every
     * ordinary service rather than present and false, so {@code standby === true} is the only way
     * to read it and a missing field can never be mistaken for a denial.</p>
     *
     * @param service the compose service name this row is about
     */
    static void putStandby(final Map<String, Object> row, final String service) {
        if (Topology.standbyNames().contains(service)) {
            row.put("standby", true);
        }
    }

    /**
     * Writes {@code players} and {@code roster} onto a row - or writes neither, which is the point.
     *
     * <p>Package-private and static so that the rule above is a thing a test can hold, without a
     * Docker daemon and without an HTTP round trip: the two {@code null} checks here are the whole
     * of the "absence is not zero" contract at this end, and they are two lines that a later edit
     * could turn into {@code getOrDefault} without anything else noticing.
     *
     * @param service the compose service name this row is about - the key both maps are keyed by
     */
    static void putOnline(final Map<String, Object> row, final String service, final ServicesApi.Online online) {
        final Integer connected = online.counts().get(service);
        if (connected != null) {
            row.put("players", connected);
        }
        final List<OnlinePlayer> roster = online.roster().get(service);
        if (roster != null) {
            row.put("roster", named(roster));
        }
    }

    /**
     * The two fields of a player that leave this process, and no others.
     *
     * <p>{@code updated} stays behind because it has already been used - {@link ServicesApi} spent
     * it deciding whether this player is worth sending at all, and a second copy of it on the wire
     * would invite a second, different freshness rule in the interface. {@code subject} stays behind
     * because the row it is sitting in already is that subject.
     *
     * <p>Built as maps rather than handed over as records: this API's JSON is Gson's (see
     * {@code JavalinGson} above), and the uuid is written as its canonical 8-4-4-4-12 text - the
     * shape {@code steward-ui}'s own {@code IDENTIFIER_PATTERN} and the head service both expect.
     */
    private static List<Map<String, Object>> named(final List<OnlinePlayer> roster) {
        final List<Map<String, Object>> people = new ArrayList<>(roster.size());
        for (final OnlinePlayer player : roster) {
            final Map<String, Object> person = new LinkedHashMap<>();
            person.put("uuid", player.uuid().toString());
            person.put("name", player.name());
            people.add(person);
        }
        return List.copyOf(people);
    }

    private Optional<Map<String, Object>> service(final String name) {
        final ImageResult drift = drift().result();
        final Map<String, ServiceHold> holds = new LinkedHashMap<>();
        for (final ServiceHold hold : updates.holds()) {
            holds.put(hold.service(), hold);
        }
        return docker.containers(project).stream()
                .filter(container -> name.equals(container.service()))
                .findFirst()
                .map(container -> {
                    final Map<String, Object> row = describe(container, drift, online(), holds);
                    row.put("digests", docker.repoDigests(container.imageId()));
                    row.put("hasPlugins", Topology.hasPlugins(name));
                    disk.of(name).ifPresent(measured -> {
                        row.put("diskBytes", measured.bytes().getAsLong());
                        row.put("diskMeasuredAt", measured.at().toString());
                    });
                    row.put(
                            "logCapacity",
                            logCapacity
                                    .computeIfAbsent(
                                            name,
                                            key -> new Refreshed<>(
                                                    () -> capacity(key),
                                                    LOG_CAPACITY_TTL,
                                                    runnable -> Thread.ofVirtual()
                                                            .name("log-capacity")
                                                            .start(runnable),
                                                    Instant::now))
                                    .get());
                    return row;
                });
    }

    /** {@code backup.at}, {@code backup.days}, the zone they are read in, and the next moment. */
    private Map<String, Object> schedule() {
        final Nightly nightly = this.nightly.get();
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("backupAt", nightly.at().isBlank() ? null : nightly.at());
        // The weekdays as the file says them, not as the clock understood them: this is the
        // schedule being reported, and a word nobody can read is a thing the page should be able
        // to show as it stands rather than one that silently disappears on the way here.
        answer.put("backupDays", nightly.days());
        answer.put("zone", nightly.zone().getId());
        answer.put(
                "nextBackupAt",
                NightlyClock.next(nightly.at(), nightly.days(), nightly.zone(), ZonedDateTime.now(nightly.zone()))
                        .map(next -> next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .orElse(null));
        // The optional update clock, read exactly the same way. A blank update.at is no schedule,
        // and then there is no next moment either.
        answer.put("updateAt", nightly.updateAt().isBlank() ? null : nightly.updateAt());
        answer.put("updateDays", nightly.updateDays());
        answer.put(
                "nextUpdateAt",
                nightly.updateAt().isBlank()
                        ? null
                        : NightlyClock.next(
                                        NightlyClock.Job.UPDATE,
                                        nightly.updateAt(),
                                        nightly.updateDays(),
                                        nightly.zone(),
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
        answer.put(
                "containerLimits",
                "No container sets a memory limit, so every percentage here is a share of the whole host.");
        return answer;
    }

    /**
     * `/api/updates/available`'s body: the whole resolve, flattened, plus the age of the reading.
     *
     * <h2>Every row is carried, not only the ones with work in them</h2>
     * A list of "what is outdated" cannot be told apart from a list of "what could not be asked",
     * and those two must never look alike - that is the entire reason
     * {@link Change.Status#UNRESOLVED} is a status and not an omission. So the answer is one row
     * per artefact with its status on it, and what the page shows is the page's decision.
     *
     * <p>{@code hasWork} and {@code hasFailures} are sent rather than counted in the browser for
     * the same reason {@code PlanReport} exists: the worker is the only thing that decides what an
     * update means, and a second opinion assembled from the rows is how the two drift apart.</p>
     */
    private Map<String, Object> availability(final Available reading) {
        final UpdatePlan plan = reading.plan();
        final List<Map<String, Object>> changes = new ArrayList<>();
        for (final Change change : plan.changes()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            // The pack has no service - see PlanReport. Left off rather than sent as "" so that a
            // reader cannot mistake it for a service whose name happens to be empty.
            if (change.service() != null) {
                row.put("service", change.service());
            }
            row.put("artifact", change.artifact());
            row.put("status", change.status().name());
            row.put("work", change.status().isWork());
            row.put("failure", change.status().isFailure());
            // Work a run would not do: another row of the same service could not be checked, and
            // the applier leaves that whole service alone. Sent so the page shows an update only
            // where a run would install it.
            row.put(
                    "held",
                    change.status().isWork() && change.service() != null && plan.blocker(change.service()) != null);
            if (change.installed() != null) {
                row.put("installed", change.installed());
            }
            if (change.wanted() != null) {
                // The version for a person and the filename for the comparison, because those are
                // two different strings and the report has been wrong about which is which:
                // PacketEvents publishes version `2.13.0+spigot` as `packetevents-spigot-2.13.0.jar`.
                row.put("version", change.wanted().version());
                row.put("fileName", change.wanted().fileName());
            }
            if (change.note() != null) {
                row.put("note", change.note());
            }
            changes.add(row);
        }

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("checkedAt", reading.checkedAt().toString());
        answer.put("resolvedAt", plan.resolvedAt().toString());
        if (plan.seasonTag() != null) {
            answer.put("seasonTag", plan.seasonTag());
        }
        answer.put("seasonPrerelease", plan.seasonPrerelease());
        answer.put("hasWork", plan.hasWork());
        answer.put("hasFailures", plan.hasFailures());
        answer.put("changes", changes);
        answer.put(
                "unclaimed",
                plan.unclaimed().stream()
                        .map(one -> Map.of("service", one.service(), "fileName", one.fileName()))
                        .toList());
        answer.put("notes", plan.notes());
        return answer;
    }

    /**
     * `/api/alert-level`'s body: everything that is wrong, and the three raw measurements.
     *
     * <h2>Measurements, never verdicts, for the three configured thresholds</h2>
     * {@code diskPercent}, {@code memoryPercent} and {@code backupAgeHours} are readings and not
     * alarms: the numbers they would be compared against live in steward-ui's own
     * {@code UiSpec.AlertSpec} and are sent nowhere. See {@link AlertLevel}'s class note - this is
     * how a threshold alarm reaches a lock screen without a second copy of the threshold existing
     * in this process's config file.
     *
     * <p>{@code level}, {@code subject} and {@code path} stay at the top level, unchanged, for a
     * reader that only wants "how bad is it right now". They are the worst of {@code triggers} and
     * are not a separate opinion.</p>
     */
    private Map<String, Object> alertLevel() {
        final AlertLevel.Reading reading = AlertLevel.of(serviceTable(), archives(), hostNumbers(), Instant.now());
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("level", reading.level().name().toLowerCase(java.util.Locale.ROOT));
        answer.put("subject", reading.subject());
        answer.put("path", reading.path());
        final List<Map<String, Object>> triggers = new ArrayList<>();
        for (final AlertLevel.Trigger trigger : reading.triggers()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", trigger.kind().name().toLowerCase(java.util.Locale.ROOT));
            row.put("level", trigger.level().name().toLowerCase(java.util.Locale.ROOT));
            row.put("subject", trigger.subject());
            row.put("path", trigger.path());
            triggers.add(row);
        }
        answer.put("triggers", triggers);
        // Absent rather than a number when nothing could be measured: a missing key is "nobody
        // looked", and steward-ui then compares nothing rather than comparing a zero.
        if (reading.diskPercent() != null) {
            answer.put("diskPercent", reading.diskPercent());
        }
        if (reading.memoryPercent() != null) {
            answer.put("memoryPercent", reading.memoryPercent());
        }
        if (reading.backupAgeHours() != null) {
            answer.put("backupAgeHours", reading.backupAgeHours());
        }
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
        all.sort(
                (left, right) -> String.valueOf(right.get("modified")).compareTo(String.valueOf(left.get("modified"))));
        return all;
    }

    /**
     * Streams one archive or dump out of {@link #backups}, for steward/95's detail page.
     *
     * <h2>Two checks, not one</h2>
     * {@link eu.nordtal.s2.steward.worker.backup.TarSnapshots#isFinishedArchive} refuses anything
     * that is not a finished archive name - but that regex's {@code .} matches a {@code /} exactly
     * as readily as any other character, so {@code ../../etc/passwd-20260913T044507Z.tar.zst}
     * matches it too (proven in {@code TarSnapshotsTest}). The second check is the one that
     * actually stops that: resolve the name against {@link #backups} and refuse anything whose
     * normalised path has left that directory. Neither check alone is the defence; both together
     * are.
     *
     * <h2>Streamed, never buffered</h2>
     * These files are hundreds of megabytes, so the body is an open {@link java.io.InputStream}
     * handed to {@code ctx.result} rather than a byte array read in full first. Javalin's own
     * documentation says {@code ctx.result(InputStream)} writes and closes the stream for the
     * caller; that was not independently re-verified against the Javalin 7.2.3 jar in this session
     * and is worth a second look before this route sees real traffic (noted in the ticket).
     */
    private void downloadBackup(final Context ctx, final String name) {
        if (!TarSnapshots.isFinishedArchive(name)) {
            throw new BadRequestResponse("not the name of a finished backup: " + name);
        }
        final Path resolved = backups.resolve(name).normalize();
        if (!resolved.startsWith(backups.normalize()) || !resolved.getParent().equals(backups.normalize())) {
            // Never reached by a plain filename, since `{name}` cannot itself carry a `/` - this
            // is the second, independent line the javadoc above promises, for the day the first
            // one is weakened without anybody noticing.
            throw new BadRequestResponse("not the name of a finished backup: " + name);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new NotFoundResponse("no such backup: " + name);
        }
        final long size;
        try {
            size = Files.size(resolved);
        } catch (IOException unreadable) {
            throw new NotFoundResponse("no such backup: " + name);
        }
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + resolved.getFileName() + "\"");
        ctx.header("Content-Length", String.valueOf(size));
        try {
            ctx.result(Files.newInputStream(resolved));
        } catch (IOException gone) {
            throw new NotFoundResponse("no such backup: " + name);
        }
    }

    /**
     * What the console shows below the live lines when Docker alone cannot fill the window
     *: the earlier runs out of the volume, oldest first so that the browser's
     * arrival order stays the log's order, and an {@code end} event first of all when nothing older
     * is left anywhere. The follow that comes after starts with the same {@code tail}, so the two
     * meet where Docker's own log begins.
     */
    private void backlog(
            final SseClient client,
            final String containerId,
            final String name,
            final String tail,
            final boolean multiplexed) {
        final int wanted;
        try {
            wanted = Integer.parseInt(tail);
        } catch (NumberFormatException all) {
            return;
        }
        final List<String> docker = this.docker.recentLines(containerId, wanted, multiplexed);
        if (docker.size() >= wanted) {
            return;
        }
        final LogArchive.Backlog earlier = archive.before(name, oldest(docker), wanted - docker.size());
        if (earlier.exhausted()) {
            client.sendEvent("end", "Nothing older.");
        }
        for (final LogArchive.Run run : earlier.runs()) {
            client.sendEvent("run", run.label());
            for (final String line : run.lines()) {
                if (client.terminated()) {
                    throw new Gone();
                }
                client.sendEvent("line", line);
            }
        }
    }

    /** The timestamp Docker put in front of the first line, or now when there is none. */
    static Instant oldest(final List<String> dockerLines) {
        if (!dockerLines.isEmpty()) {
            final String first = dockerLines.getFirst();
            final int space = first.indexOf(' ');
            if (space > 0) {
                try {
                    return Instant.parse(first.substring(0, space));
                } catch (java.time.format.DateTimeParseException notStamped) {
                    // falls through to now
                }
            }
        }
        return Instant.now();
    }

    /** Lines the console can offer: Docker's, then the archive's, up to the highest step. */
    private int capacity(final String service) {
        final String containerId = containerOf(service).orElse(null);
        if (containerId == null) {
            return 0;
        }
        final boolean multiplexed = !docker.inspect(containerId).tty();
        final List<String> lines = docker.recentLines(containerId, LOG_CAPACITY_MAX, multiplexed);
        if (lines.size() >= LOG_CAPACITY_MAX) {
            return LOG_CAPACITY_MAX;
        }
        return lines.size()
                + archive.before(service, oldest(lines), LOG_CAPACITY_MAX - lines.size())
                        .lineCount();
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
    private void goneOnShutdown(final SseClient client, final DockerSocket.Stream stream, final String name) {
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
