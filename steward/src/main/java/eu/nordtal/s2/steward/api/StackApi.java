package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.database.update.ServiceHold;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.steward.backup.NightlyClock;
import eu.nordtal.s2.steward.docker.Console;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerException;
import eu.nordtal.s2.steward.docker.DockerOps;
import eu.nordtal.s2.steward.host.HostMetrics;
import eu.nordtal.s2.steward.host.HostSnapshot;
import eu.nordtal.s2.steward.ops.ImageResult;
import eu.nordtal.s2.steward.plan.Change;
import eu.nordtal.s2.steward.plan.Topology;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import eu.nordtal.s2.steward.push.AlertReading;
import io.javalin.config.JavalinConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
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
 * The stack's routes: services, logs, the console, settings, messages, the host, backups and plugins.
 *
 * Stopping and starting are not here; they are rows in the run inbox. Every route sits behind the web's gate.
 */
public final class StackApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StackApi.class);

    final Docker docker;
    final Console console;
    private final HostMetrics host;
    final String project;
    final Path backups;

    /**
     * What {@code backup.at} and {@code update.at} say, and in which zone.
     *
     * @param at {@code HH:mm} in this container's own time zone, or blank for no nightly backup
     * @param updateAt the same for the scheduled update, blank (the default) for none
     * @param zone the network's default zone, from its settings, not the browser's
     */
    public record Nightly(String at, List<String> days, String updateAt, List<String> updateDays, ZoneId zone) {

        /** No scheduled update, which is what a config without the {@code update} section says. */
        public Nightly(final String at, final List<String> days, final ZoneId zone) {
            this(at, days, "", List.of(), zone);
        }
    }

    /** Asked on every request, because a save of steward.yml changes it without a restart. */
    private final Supplier<Nightly> nightly;

    /** The plugin routes, or null without a database, when every route answers 503. */
    private final @Nullable PluginsApi managedPlugins;

    /** Every process's settings, or {@code null} without a database. */
    private final @Nullable SettingsApi settings;

    /** The live console SSE stream and its shutdown ordering. */
    final LogFollows logFollows;

    private final ServiceRows serviceRows;

    /**
     * How long a registry answer is good for.
     *
     * The response carries {@code driftCheckedAt}, so the page says how old the comparison is.
     */
    private static final Duration DRIFT_TTL = Duration.ofMinutes(1);
    /** The console's steps are 1000, 5000 and 10000 lines; counting past the top one buys nothing. */
    static final int LOG_CAPACITY_MAX = 10_000;

    private static final Duration LOG_CAPACITY_TTL = Duration.ofMinutes(5);

    /** One comparison and the moment it was made, as one value, so a row and its age always match. */
    private record Drift(ImageResult result, Instant checkedAt) {}

    /** One daemon thread for background refreshes, which never run two at once. */
    private final ExecutorService driftRefresh = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "steward-drift");
        thread.setDaemon(true);
        return thread;
    });

    private final Refreshed<Drift> drift;

    /**
     * How long a resolve is good for.
     *
     * Looking is not running: the resolve writes nothing, and a page open asks at most every six hours.
     */
    private static final Duration AVAILABLE_TTL = Duration.ofHours(6);

    /** One resolve and the moment it was made, for the same reason {@link Drift} is one value. */
    record Available(UpdatePlan plan, Instant checkedAt) {}

    /**
     * The resolve, or {@code null} where this API has no sources to ask.
     *
     * Null rather than an empty plan, which would claim everything is current; the endpoint answers 503.
     */
    final @Nullable Refreshed<Available> available;

    final MessagesApi messages;
    final ActionsApi actions;
    /** The Disk field of one service's page; never part of the service table. */
    private final DiskUsage disk;
    /** The runs before the container, out of the server's own rotated logs. */
    final LogArchive archive;
    /** How many lines the console can fill per service, Docker plus archive, capped at the top step. */
    private final Map<String, Refreshed<Integer>> logCapacity = new ConcurrentHashMap<>();

    /** The API without player counts, plugins, inboxes or a resolve, as a test against the real daemon needs it. */
    public StackApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final Path configs,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly,
            final Clock clock) {
        this(
                docker,
                ops,
                console,
                host,
                project,
                backups,
                configs,
                null,
                updates,
                audit,
                () -> nightly,
                null,
                null,
                null,
                null,
                null,
                null,
                clock);
    }

    private final Clock clock;

    /**
     * The whole API.
     *
     * @param volumesRoot where the four Minecraft volumes are mounted, used only to find a standalone module's bundle
     *     jar; {@code null} skips it
     * @param updates the run inbox, sharing one directory with the run loop
     * @param audit {@code audit_log}, for {@link ActionsApi}
     * @param online where the player counts and the player list come from, or {@code null} without a database
     * @param resolve {@code Runs#resolve}, asked again when the cache ages; {@code null} makes the endpoint answer 503
     * @param managedPlugins the plugin routes, or {@code null} without a database, when they answer 503
     * @param botInbox the bot's inbox, or {@code null} without a database, when a bot bundle save asks for a
     *     restart
     * @param nightly the schedule as it stands right now, changed with the steward settings
     * @param reloads asks a server to re-read a saved bundle, or {@code null} without a database, when a save asks for
     *     a restart
     * @param settings every process's settings, or {@code null} without a database, when their routes answer 503
     */
    public StackApi(
            final Docker docker,
            final DockerOps ops,
            final Console console,
            final HostMetrics host,
            final String project,
            final Path backups,
            final Path configs,
            final @Nullable Path volumesRoot,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Supplier<Nightly> nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<UpdatePlan> resolve,
            final @Nullable PluginsApi managedPlugins,
            final @Nullable Inbox<BotRequest> botInbox,
            final MessagesApi.@Nullable Reloader reloads,
            final @Nullable SettingStore settings,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.managedPlugins = managedPlugins;
        this.settings = settings == null ? null : new SettingsApi(settings);
        this.docker = docker;
        this.console = console;
        this.host = host;
        this.project = project;
        this.backups = backups;
        this.nightly = nightly;
        final MessagesApi.Reloader reloader = reloads == null
                ? service -> {
                    throw new IllegalArgumentException("Steward has no database to ask " + service + " through.");
                }
                : reloads;
        this.messages = new MessagesApi(configs, volumesRoot, botInbox, reloader, Waiting.on(clock));
        // One query over two tables, not a frontend-side merge.
        this.actions = new ActionsApi(updates, audit);
        // Its own virtual thread per refresh, so a du never waits behind a registry call.
        this.disk = new DiskUsage(
                volumesRoot, runnable -> Thread.ofVirtual().name("disk-usage").start(runnable));
        this.archive = new LogArchive(volumesRoot);
        this.logFollows = new LogFollows(docker, archive, clock);
        this.serviceRows = new ServiceRows(docker, project, updates, online);
        // Here rather than at the field, because it reads `ops`, which is a constructor argument.
        this.drift = new Refreshed<>(
                () -> new Drift(ops.images(), clock.instant()), DRIFT_TTL, driftRefresh, clock::instant);
        // The same background thread as drift: both are slow calls nobody asked for.
        this.available = resolve == null
                ? null
                : new Refreshed<>(
                        () -> new Available(resolve.get(), clock.instant()),
                        AVAILABLE_TTL,
                        driftRefresh,
                        clock::instant);
    }

    /** Puts every route of this API onto {@code config}, behind the gate {@code caller} answers for. */
    public void register(final JavalinConfig config, final Caller caller) {
        Routes.register(this, config, caller);
    }

    /** Whether the Docker daemon answers; without it every container route answers 503. */
    public boolean dockerReachable() {
        return docker.isReachable();
    }

    /** Runs the first image comparison in the background, so the first {@code /api/services} need not wait for it. */
    public void warm() {
        driftRefresh.execute(() -> {
            try {
                drift.get();
            } catch (RuntimeException failed) {
                log.warn("the first image comparison failed - the next request will try again: {}", failed.toString());
            }
        });
    }

    /** The settings routes, or a 503: without a database there are no settings to show. */
    SettingsApi settings() {
        if (settings == null) {
            throw new io.javalin.http.ServiceUnavailableResponse("Steward has no database, so it holds no settings");
        }
        return settings;
    }

    /** The plugin routes, or a 503, since "no plugins" and "cannot read the table" are different answers. */
    PluginsApi plugins() {
        if (managedPlugins == null) {
            throw new io.javalin.http.ServiceUnavailableResponse(
                    "Steward has no database, so it cannot say which plugins were added");
        }
        return managedPlugins;
    }

    /** The service table, with the age of the drift comparison beside it. */
    Map<String, Object> serviceTable() {
        // Taken once, for the rows and for the sentence about them.
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

    /** The drift answer as it stands, refreshed in the background rather than on the caller's thread. */
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
                                                            docker,
                                                            project,
                                                            archive,
                                                            key,
                                                            LOG_CAPACITY_MAX,
                                                            clock.instant()),
                                                    LOG_CAPACITY_TTL,
                                                    runnable -> Thread.ofVirtual()
                                                            .name("log-capacity")
                                                            .start(runnable),
                                                    clock::instant))
                                    .get());
                    return row;
                });
    }

    /** {@code backup.at}, {@code backup.days}, the zone they are read in, and the next moment. */
    Map<String, Object> schedule() {
        final Nightly nightly = this.nightly.get();
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("backupAt", nightly.at().isBlank() ? null : nightly.at());
        // The weekdays as the file says them, not as the clock understood them.
        answer.put("backupDays", nightly.days());
        answer.put("zone", nightly.zone().getId());
        answer.put(
                "nextBackupAt",
                NightlyClock.next(
                                nightly.at(),
                                nightly.days(),
                                nightly.zone(),
                                ZonedDateTime.now(clock.withZone(nightly.zone())))
                        .map(next -> next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .orElse(null));
        // The optional update clock, read the same way; a blank update.at is no schedule.
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
                                        ZonedDateTime.now(clock.withZone(nightly.zone())))
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
        // No container sets a memory limit, so a percentage is a share of the whole machine.
        answer.put(
                "containerLimits",
                "No container sets a memory limit, so every percentage here is a share of the whole host.");
        return answer;
    }

    /**
     * `/api/updates/available`'s body: the whole resolve, flattened, plus the age of the reading.
     *
     * Every row is carried with its status, so outdated and unresolved never look alike.
     */
    Map<String, Object> availability(final Available reading) {
        final UpdatePlan plan = reading.plan();
        final List<Map<String, Object>> changes = new ArrayList<>();
        for (final Change change : plan.changes()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            // The pack has no service, so the key is left off rather than sent empty.
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
                // Version for a person, filename for the comparison, since some files carry a suffix.
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
     * Everything that is wrong right now and the three raw measurements, for the push watch to judge.
     *
     * The thresholds live in web.yml; this reading only says what was found.
     */
    public AlertReading alertReading() {
        final AlertLevel.Reading reading =
                AlertLevel.of(serviceTable(), Archives.list(backups), hostNumbers(), clock.instant());
        final List<AlertReading.Trigger> triggers = new ArrayList<>();
        for (final AlertLevel.Trigger trigger : reading.triggers()) {
            triggers.add(new AlertReading.Trigger(
                    trigger.kind().name().toLowerCase(java.util.Locale.ROOT),
                    trigger.level().name().toLowerCase(java.util.Locale.ROOT),
                    trigger.subject(),
                    trigger.path()));
        }
        return new AlertReading(triggers, reading.diskPercent(), reading.memoryPercent(), reading.backupAgeHours());
    }

    /** The body of a console POST. */
    static final class ConsoleLine {
        @Nullable
        String command;
    }

    /** Stops every open log follow, then the drift refresh; called before Jetty stops. */
    @Override
    public void close() {
        logFollows.close();
        // Not awaited: a registry call has its own timeout, and Refreshed handles the rejection a later reader gets.
        driftRefresh.shutdownNow();
    }
}
