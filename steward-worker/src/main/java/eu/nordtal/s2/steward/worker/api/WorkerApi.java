package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.access.AccessRequests;
import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.update.ServiceHold;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.backup.NightlyClock;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.host.HostSnapshot;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.plan.Change;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.file.Path;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What steward-ui is allowed to ask this container.
 *
 * Why this exists at all: §3 of the concept keeps the docker socket away from the web interface, and this is the
 * other end of that decision: the interface owns no socket, so everything it knows about a container - state,
 * health, image drift, the log, a console line - arrives through here. The trade is named rather than hidden:
 * whoever takes over steward-ui can call these endpoints, so what they can reach is exactly this list and no more.
 * Stopping, starting and recreating are not on it; those happen by writing a row into {@code update_request}, which
 * is countable, cancellable and carries a countdown every player sees.
 *
 * The token is not optional: The service refuses to serve without one, for the same reason steward-deployer does: a
 * console that anybody on the network can type into is a remote shell with a nicer font. It is a shared secret in
 * the host's {@code .env}, given to both containers.
 */
public final class WorkerApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerApi.class);

    final Docker docker;
    final Console console;
    private final HostMetrics host;
    final String project;
    final Path backups;
    final String token;

    /**
     * What {@code backup.at} and {@code update.at} say, and in which zone.
     *
     * So the interface can offer "tonight" and show when either clock fires next.
     *
     * @param at       {@code HH:mm} in this container's own time zone, or blank for no nightly backup
     * @param updateAt the same for the scheduled update, blank - the default - for none
     * @param zone     this container's zone - compose sets {@code TZ}, and it is not the browser's
     */
    public record Nightly(String at, List<String> days, String updateAt, List<String> updateDays, ZoneId zone) {

        /** No scheduled update, which is what a config without the {@code update} section says. */
        public Nightly(final String at, final List<String> days, final ZoneId zone) {
            this(at, days, "", List.of(), zone);
        }
    }

    /** Asked on every request, because a save of steward.yml changes it without a restart. */
    private final Supplier<Nightly> nightly;

    /**
     * The plugin list, the Modrinth search, and the two buttons.
     *
     * Null in a deployment with no database, because the added plugins are a table. Every route of it then answers 503
     * rather than an empty list - see the constructor.
     */
    private final @Nullable PluginsApi managedPlugins;

    /** The live console SSE stream and its shutdown ordering; see {@link LogFollows} for why it is its own class. */
    final LogFollows logFollows;

    private final ServiceRows serviceRows;

    /**
     * How long a registry answer is good for.
     *
     * A minute, because drift is caused by a push and not by a page refresh, and because the answer is read by whoever
     * is looking at the start page - which refreshes on a timer. The response carries {@code driftCheckedAt} so the
     * interface can say how old the comparison is rather than implying it was made just now.
     */
    private static final Duration DRIFT_TTL = Duration.ofMinutes(1);
    /** The console's steps are 1000, 5000 and 10000 lines; counting past the top one buys nothing. */
    static final int LOG_CAPACITY_MAX = 10_000;

    private static final Duration LOG_CAPACITY_TTL = Duration.ofMinutes(5);

    /**
     * One comparison and the moment it was made, as one value.
     *
     * They were two fields, read one after the other: {@code services()} took the result and {@code serviceTable()}
     * then read the timestamp, with a TTL expiry possible in between. The page could therefore draw a green tick
     * from one comparison beside the words "compared a minute ago" belonging to another - this column exists because
     * image drift went unnoticed for four releases, so a row and its age have to be the same reading.
     */
    private record Drift(ImageResult result, Instant checkedAt) {}

    /**
     * One thread, and it belongs to nobody's request.
     *
     * A daemon, because a registry call in flight must not hold this process up on the way out, and one rather than a
     * pool because {@link Refreshed} never has two refreshes going at once.
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
     * What it answers, and what it still must not do: {@code UpdateServer} opens with "the first rule of this module
     * is that nothing updates on a schedule", and that rule is untouched here: looking is not running.
     * This asks Modrinth, GitHub and the Fill API what is newest and compares it with the jars in the volumes - the
     * same {@code Runs#resolve} a run starts with, which writes nothing, anywhere. No row is written into
     * {@code update_request} and no container is touched. A run is still only ever a row somebody asked for.
     *
     * Why a cache and not a clock: A timer would ask on a schedule whether or not anybody wanted to know, which is
     * the shape that gets a token rate-limited for nothing. {@link Refreshed} asks when the page is opened and hands
     * the previous answer over while a new one is fetched behind it, so ten admins looking at once cost one round of
     * API calls and nobody waits on the network. A week nobody opens the page is a week nothing is asked - correct,
     * because there was nobody to show it to.
     *
     * Six hours, because a plugin release is a thing that happens a few times a month and the answer carries
     * {@code checkedAt} beside it. The page says how old the reading is rather than implying it was taken just now,
     * exactly as the drift column does one field up.
     */
    private static final Duration AVAILABLE_TTL = Duration.ofHours(6);

    /** One resolve and the moment it was made, for the same reason {@link Drift} is one value. */
    record Available(UpdatePlan plan, Instant checkedAt) {}

    /**
     * The resolve, or {@code null} where this API has no sources to ask - every test that builds a {@link WorkerApi}
     * without one, and any deployment where the wiring chose not to.
     *
     * Null rather than a supplier returning an empty plan, because the two are different answers: an empty plan says
     * "everything is current" and there is nothing behind it to say that. The endpoint answers 503 instead, which the
     * page can draw as "could not look" - the distinction {@link Change.Status#UNRESOLVED} exists for, one level up.
     */
    final @Nullable Refreshed<Available> available;

    private @Nullable Javalin app;

    final ConfigApi configs;
    final MessagesApi messages;
    final ActionsApi actions;
    /** The Disk field of one service's page; never part of the service table. */
    private final DiskUsage disk;
    /** The runs before the container, out of the server's own rotated logs. */
    final LogArchive archive;
    /** How many lines the console can fill per service, Docker plus archive, capped at the top step. */
    private final Map<String, Refreshed<Integer>> logCapacity = new ConcurrentHashMap<>();

    public WorkerApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly) {
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
     *                    update directory is read once more, for the holds - see {@code ServiceRows}
     *                    for why that one reading is worth the exception to "this class talks to
     *                    Docker and the filesystem, never the database" (§3)
     */
    public WorkerApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly) {
        this(docker, ops, console, host, project, backups, token, configs, volumesRoot, updates, audit, nightly, null);
    }

    /**
     * @param online where the player counts and the player list come from, or {@code null} for a
     *               deployment with no database behind this API. A {@link ServicesApi} and not the
     *               two directories behind it: what it reads (two tables today) is its business,
     *               and what this class needs is one answer per response. See {@link ServicesApi}
     *               for why a subject it cannot vouch for is left out of the answer rather than
     *               sent as {@code 0} or an empty list.
     */
    public WorkerApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly,
            final @Nullable ServicesApi online) {
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
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<UpdatePlan> resolve) {
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
     * @param managedPlugins the four routes behind "the plugins on this server", or {@code null} in
     *                       a deployment with no database - they then answer 503, for the reason
     *                       {@link #available} gives: an empty
     *                       plugin list and a worker that cannot read the table are different
     *                       answers, and guessing the friendlier one would be a lie about what is
     *                       installed
     */
    public WorkerApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<UpdatePlan> resolve,
            final @Nullable PluginsApi managedPlugins) {
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
     * @param accessInbox the bot's request inbox, or {@code null} in a deployment with no database
     *                    - saving the bot's messages then answers that a restart is needed, which
     *                    is what is true there
     */
    public WorkerApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<UpdatePlan> resolve,
            final @Nullable PluginsApi managedPlugins,
            final @Nullable AccessRequests accessInbox) {
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
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final String token,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Supplier<Nightly> nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<UpdatePlan> resolve,
            final @Nullable PluginsApi managedPlugins,
            final @Nullable AccessRequests accessInbox,
            final Runnable reReadOwn) {
        this.managedPlugins = managedPlugins;
        this.docker = docker;
        this.console = console;
        this.host = host;
        this.project = project;
        this.backups = backups;
        this.token = token;
        this.nightly = nightly;
        // Lives here, not in steward-ui: every file it touches is 0600 root:root, and steward-ui is not root.
        this.configs = new ConfigApi(configs, console::send, java.util.Map.of(ConfigApi.OWN_CONFIG, reReadOwn));
        // Not a config file - see MessagesApi's own javadoc for why it is kept apart rather than folded in here.
        this.messages = new MessagesApi(configs, volumesRoot, accessInbox, console::send);
        // See ActionsApi's own javadoc for why this is one query over two tables and not a frontend-side merge.
        this.actions = new ActionsApi(updates, audit);
        // Its own virtual thread per refresh, not driftRefresh: a queued du must not age behind a registry call.
        this.disk = new DiskUsage(
                volumesRoot, runnable -> Thread.ofVirtual().name("disk-usage").start(runnable));
        this.archive = new LogArchive(volumesRoot);
        this.logFollows = new LogFollows(docker, archive);
        this.serviceRows = new ServiceRows(docker, project, updates, online);
        // Here rather than at the field, because it reads `ops`, which is a constructor argument.
        this.drift =
                new Refreshed<>(() -> new Drift(ops.images(), Instant.now()), DRIFT_TTL, driftRefresh, Instant::now);
        // The same background thread as drift: both are slow calls nobody asked for, and Refreshed never runs two.
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
        app = Javalin.create(config -> Routes.register(this, config)).start(port);

        log.info("the internal API is on {} - steward-ui reads the daemon through it", port);

        // Once, before anybody asks: without this, the first /api/services blocks on the registry call itself.
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
     * 503, not an empty list, for the same reason {@code /api/updates/available} answers 503: "no plugins" and
     * "this worker cannot read the table" are different sentences, and drawing the friendlier one claims a server
     * runs nothing.
     */
    PluginsApi plugins() {
        if (managedPlugins == null) {
            throw new io.javalin.http.ServiceUnavailableResponse(
                    "this worker has no database, so it cannot say which plugins were added");
        }
        return managedPlugins;
    }

    /**
     * The service table, with the age of the drift comparison beside it.
     *
     * An envelope rather than a bare array, because the interface has to be able to say "images compared a minute ago".
     * A page that draws a green tick next to an answer cached for an unknown length of time is making a promise it
     * cannot keep - and image drift going unnoticed for four releases is the failure this whole column exists to
     * prevent.
     */
    Map<String, Object> serviceTable() {
        // Taken once, for the rows AND for the sentence about them.
        final Drift drift = drift();
        final List<Map<String, Object>> rows = serviceRows.rows(drift.result());
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
     * The drift answer as it stands, which is not necessarily the newest one there could be.
     *
     * See {@link Refreshed} for why this no longer reads the registry on the caller's thread. The short of it: it
     * used to, and steward-ui's ten-second deadline ran out on one request in every sixty while the interface
     * logged that a healthy service could not be reached.
     */
    private Drift drift() {
        return drift.get();
    }

    Optional<Map<String, Object>> service(final String name) {
        final ImageResult drift = drift().result();
        final Map<String, ServiceHold> holds = serviceRows.holds();
        return docker.containers(project).stream()
                .filter(container -> name.equals(container.service()))
                .findFirst()
                .map(container -> {
                    final Map<String, Object> row = serviceRows.describe(container, drift, serviceRows.online(), holds);
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
                                                    () -> Archives.capacity(
                                                            docker, project, archive, key, LOG_CAPACITY_MAX),
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
    Map<String, Object> schedule() {
        final Nightly nightly = this.nightly.get();
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("backupAt", nightly.at().isBlank() ? null : nightly.at());
        // The weekdays as the file says them, not as the clock understood them - a schedule being reported.
        answer.put("backupDays", nightly.days());
        answer.put("zone", nightly.zone().getId());
        answer.put(
                "nextBackupAt",
                NightlyClock.next(nightly.at(), nightly.days(), nightly.zone(), ZonedDateTime.now(nightly.zone()))
                        .map(next -> next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .orElse(null));
        // The optional update clock, read the same way - a blank update.at is no schedule and no next moment.
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

    Map<String, Object> hostNumbers() {
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
        // No container sets a memory limit, so a percentage is a share of the whole machine, not a container budget.
        answer.put(
                "containerLimits",
                "No container sets a memory limit, so every percentage here is a share of the whole host.");
        return answer;
    }

    /**
     * `/api/updates/available`'s body: the whole resolve, flattened, plus the age of the reading.
     *
     * Every row is carried, not only the ones with work in them: A list of "what is outdated" cannot be told apart from
     * a list of "what could not be asked", and those two must never look alike - that is the entire reason
     * {@link Change.Status#UNRESOLVED} is a status and not an omission. So the answer is one row per artefact with its
     * status on it, and what the page shows is the page's decision.
     *
     * {@code hasWork} and {@code hasFailures} are sent rather than counted in the browser for the same reason
     * {@code PlanReport} exists: the worker is the only thing that decides what an update means, and a second opinion
     * assembled from the rows is how the two drift apart.
     */
    Map<String, Object> availability(final Available reading) {
        final UpdatePlan plan = reading.plan();
        final List<Map<String, Object>> changes = new ArrayList<>();
        for (final Change change : plan.changes()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            // The pack has no service - see PlanReport. Left off rather than sent as "" to avoid an empty name.
            if (change.service() != null) {
                row.put("service", change.service());
            }
            row.put("artifact", change.artifact());
            row.put("status", change.status().name());
            row.put("work", change.status().isWork());
            row.put("failure", change.status().isFailure());
            // Work a run would not do: another row of the same service failed to check, so the applier skips it.
            row.put(
                    "held",
                    change.status().isWork() && change.service() != null && plan.blocker(change.service()) != null);
            if (change.installed() != null) {
                row.put("installed", change.installed());
            }
            if (change.wanted() != null) {
                // Version for a person, filename for the comparison: PacketEvents' `2.13.0+spigot` files differently.
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
     * Measurements, never verdicts, for the three configured thresholds: {@code diskPercent}, {@code memoryPercent} and
     * {@code backupAgeHours} are readings and not alarms: the numbers they would be compared against live in
     * steward-ui's own {@code UiSpec.AlertSpec} and are sent nowhere. See {@link AlertLevel}'s class note for how a
     * threshold alarm reaches a lock screen without a second copy of the threshold in this process's own config file.
     *
     * {@code level}, {@code subject} and {@code path} stay at the top level, unchanged, for a reader that only wants
     * "how bad is it right now". They are the worst of {@code triggers} and are not a separate opinion.
     */
    Map<String, Object> alertLevel() {
        final AlertLevel.Reading reading =
                AlertLevel.of(serviceTable(), Archives.list(backups), hostNumbers(), Instant.now());
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
        // Absent, not a number, when nothing could be measured: a missing key reads "nobody looked", not a zero.
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

    /** The body of a console POST. */
    static final class ConsoleLine {
        @Nullable
        String command;
    }

    /**
     * Stops every open log follow, then the drift refresh, then Jetty.
     *
     * See {@link LogFollows#close()} for why the follows come first and in that particular order.
     */
    @Override
    public void close() {
        logFollows.close();
        // Not awaited: a registry call has its own timeout, and Refreshed handles the rejection a later reader gets.
        driftRefresh.shutdownNow();
        if (app != null) {
            app.stop();
        }
    }
}
