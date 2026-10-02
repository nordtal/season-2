package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.steward.alert.StackReading;
import eu.nordtal.s2.steward.backup.NightlyClock;
import io.javalin.config.JavalinConfig;
import java.io.InputStream;
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

    /** The only way to Docker and the volumes. */
    final AgentClient agent;

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

    /** Asked on every request, because a save of the steward group changes it without a restart. */
    private final Supplier<Nightly> nightly;

    /** The plugin routes, or null without a database, when every route answers 503. */
    private final @Nullable PluginsForward managedPlugins;

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
    record Available(String plan, Instant checkedAt) {}

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
    /** How many lines the console can fill per service, Docker plus archive, capped at the top step. */
    private final Map<String, Refreshed<Integer>> logCapacity = new ConcurrentHashMap<>();

    /** The API without player counts, plugins, inboxes or a resolve, as a test against the real daemon needs it. */
    public StackApi(
            final AgentClient agent,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Nightly nightly,
            final Clock clock) {
        this(agent, updates, audit, () -> nightly, null, null, null, null, null, null, clock);
    }

    private final Clock clock;

    /**
     * The whole API.
     *
     * @param updates the run inbox, sharing one directory with the run loop
     * @param audit {@code audit_log}, for {@link ActionsApi}
     * @param online where the player counts and the player list come from, or {@code null} without a database
     * @param resolve steward-agent's resolve as its JSON, asked again when the cache ages; {@code null} makes the
     *     endpoint answer 503
     * @param managedPlugins the plugin routes, or {@code null} without a database, when they answer 503
     * @param botInbox the bot's inbox, or {@code null} without a database, when a bot bundle save asks for a
     *     restart
     * @param nightly the schedule as it stands right now, changed with the steward settings
     * @param reloads asks a server to re-read a saved bundle, or {@code null} without a database, when a save asks for
     *     a restart
     * @param settings every process's settings, or {@code null} without a database, when their routes answer 503
     */
    public StackApi(
            final AgentClient agent,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Supplier<Nightly> nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<String> resolve,
            final @Nullable PluginsForward managedPlugins,
            final @Nullable Inbox<BotRequest> botInbox,
            final MessagesApi.@Nullable Reloader reloads,
            final @Nullable SettingStore settings,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.managedPlugins = managedPlugins;
        this.settings = settings == null ? null : new SettingsApi(settings);
        this.agent = agent;
        this.nightly = nightly;
        final MessagesApi.Reloader reloader = reloads == null
                ? service -> {
                    throw new IllegalArgumentException("Steward has no database to ask " + service + " through.");
                }
                : reloads;
        this.messages = new MessagesApi(agent, botInbox, reloader, Waiting.on(clock));
        // One query over two tables, not a frontend-side merge.
        this.actions = new ActionsApi(updates, audit);
        // Its own virtual thread per refresh, so a du never waits behind a registry call.
        this.disk = new DiskUsage(
                agent, runnable -> Thread.ofVirtual().name("disk-usage").start(runnable), clock::instant);
        this.logFollows = new LogFollows(agent);
        this.serviceRows = new ServiceRows(agent, updates, online);
        this.drift = new Refreshed<>(
                () -> new Drift(agent.images(), clock.instant()), DRIFT_TTL, driftRefresh, clock::instant);
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

    /** Whether steward-agent answers; without it every container route says it could not be reached. */
    public boolean agentReachable() {
        return agent.isReachable();
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
    PluginsForward plugins() {
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
        return agent.container(name).map(container -> {
            final Map<String, Object> row = serviceRows.describe(
                    container, drift, serviceRows.online(), serviceRows.holds(), serviceRows.consoles());
            row.put("digests", container.digests() == null ? List.of() : container.digests());
            row.put("hasPlugins", Topology.hasPlugins(name));
            disk.of(name).ifPresent(measured -> {
                row.put("diskBytes", measured.bytes().getAsLong());
                row.put("diskMeasuredAt", measured.at().toString());
            });
            row.put("logCapacity", logCapacity(name));
            return row;
        });
    }

    /** Lines the console can offer, Docker's and the archive's, asked of the agent at most every five minutes. */
    private int logCapacity(final String name) {
        return logCapacity
                .computeIfAbsent(
                        name,
                        key -> new Refreshed<>(
                                () -> agent.logCapacity(key, LOG_CAPACITY_MAX),
                                LOG_CAPACITY_TTL,
                                runnable ->
                                        Thread.ofVirtual().name("log-capacity").start(runnable),
                                clock::instant))
                .get();
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
        final AgentWire.Host host;
        try {
            host = agent.host();
        } catch (final InternalClient.Failure unreachable) {
            answer.put("unreadable", AgentClient.sentence(unreachable));
            return answer;
        }
        final AgentWire.HostNumbers numbers = host.numbers();
        if (numbers != null) {
            answer.put("load1", numbers.load1());
            answer.put("cpus", numbers.cpus());
            if (numbers.cpuPercent() != null) {
                answer.put("cpuPercent", numbers.cpuPercent());
            }
            answer.put("memoryTotalBytes", numbers.memoryTotalBytes());
            answer.put("memoryAvailableBytes", numbers.memoryAvailableBytes());
            answer.put("diskTotalBytes", numbers.diskTotalBytes());
            answer.put("diskUsedBytes", numbers.diskUsedBytes());
        } else {
            answer.put("unreadable", String.valueOf(host.unreadable()));
        }
        if (host.dockerDiskUnreadable() == null) {
            answer.put("imagesBytes", host.imagesBytes());
            answer.put("volumesBytes", host.volumesBytes());
        } else {
            answer.put("dockerDiskUnreadable", host.dockerDiskUnreadable());
        }
        // No container sets a memory limit, so a percentage is a share of the whole machine.
        answer.put(
                "containerLimits",
                "No container sets a memory limit, so every percentage here is a share of the whole host.");
        return answer;
    }

    /** What is actually on the disk, newest first, not what a run reported. */
    List<AgentWire.Archive> archives() {
        return agent.archives();
    }

    /**
     * Streams one finished archive from the agent to the browser as a download, never buffered.
     *
     * The agent refuses a name that is not a finished archive with a 400 and a missing one with a 404.
     */
    void download(final io.javalin.http.Context ctx, final String name) {
        final InputStream body = agent.archive(name);
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
        // The length from the list, so the browser can show progress; an archive pruned meanwhile has none.
        archives().stream()
                .filter(archive -> archive.name().equals(name))
                .findFirst()
                .ifPresent(archive -> ctx.header("Content-Length", String.valueOf(archive.bytes())));
        ctx.result(body);
    }

    /** `/api/updates/available`'s body: the agent's whole resolve, plus the age of the reading. */
    Map<String, Object> availability(final Available reading) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("checkedAt", reading.checkedAt().toString());
        answer.putAll(eu.nordtal.s2.common.json.Json.decode(
                reading.plan(), new com.google.gson.reflect.TypeToken<LinkedHashMap<String, Object>>() {}));
        return answer;
    }

    /** What every measured alert is judged on: the service table, the archives on disk and the host's numbers. */
    public StackReading stackReading() {
        final ImageResult images = drift().result();
        final List<StackReading.Service> services = new ArrayList<>();
        for (final Map<String, Object> row : serviceRows.rows(images)) {
            services.add(new StackReading.Service(
                    String.valueOf(row.get("service")),
                    String.valueOf(row.get("state")),
                    row.get("health") instanceof String health ? health : null,
                    Boolean.TRUE.equals(row.get("standby")) || row.containsKey("hold"),
                    "OUTDATED".equals(row.get("drift"))));
        }
        final String registryProblem = images.reached()
                ? null
                : java.util.Objects.requireNonNullElse(images.message(), "the registry did not answer");
        final List<StackReading.Archive> archives = archives().stream()
                .map(archive -> new StackReading.Archive(archive.name(), archive.modified(), archive.partial()))
                .toList();
        return new StackReading(services, registryProblem, archives, host());
    }

    /** The disk and memory numbers, or null when the agent could not read them. */
    private StackReading.@Nullable Host host() {
        final AgentWire.HostNumbers numbers;
        try {
            numbers = agent.host().numbers();
        } catch (final InternalClient.Failure unreachable) {
            return null;
        }
        return numbers == null
                ? null
                : new StackReading.Host(
                        numbers.diskUsedBytes(),
                        numbers.diskTotalBytes(),
                        numbers.memoryAvailableBytes(),
                        numbers.memoryTotalBytes());
    }

    /** The body of a console POST. */
    static final class ConsoleLine {
        @Nullable
        String command;
    }

    /** Types one line into a server's console through the agent, which logs who typed it beside the line. */
    void console(final String service, final String command, final String actor) {
        agent.console(service, command, actor);
    }

    /** Stops every open log follow, then the drift refresh; called before Jetty stops. */
    @Override
    public void close() {
        logFollows.close();
        // Not awaited: a registry call has its own timeout, and Refreshed handles the rejection a later reader gets.
        driftRefresh.shutdownNow();
    }
}
