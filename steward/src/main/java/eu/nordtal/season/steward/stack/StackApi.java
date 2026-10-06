package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.steward.alert.StackReading;
import eu.nordtal.season.steward.backup.NightlyClock;
import eu.nordtal.season.steward.live.LiveFeed;
import eu.nordtal.season.steward.live.Topic;
import eu.nordtal.season.steward.messages.MessagesApi;
import eu.nordtal.season.steward.settings.SettingsApi;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

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

    /** The lane for background refreshes, which never run two at once. */
    private final Executor driftRefresh;

    private final Refreshed<Drift> drift;

    /**
     * How long a resolve is good for.
     *
     * Looking is not running: the resolve writes nothing, and a page open asks at most every six hours.
     */
    private static final Duration AVAILABLE_TTL = Duration.ofHours(6);

    /**
     * The resolve, or {@code null} where this API has no sources to ask.
     *
     * Null rather than an empty plan, which would claim everything is current; the endpoint answers 503.
     */
    final @Nullable Refreshed<AgentWire.Resolve> available;

    final MessagesApi messages;
    final ActionsApi actions;
    private final AuditDirectory audit;
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
            final Clock clock,
            final Scheduler scheduler) {
        this(agent, updates, audit, () -> nightly, null, null, null, null, null, clock, scheduler);
    }

    private final Clock clock;
    private final Scheduler scheduler;

    /** The run inbox, where a restore is asked for like every other run. */
    private final UpdateDirectory updates;

    /**
     * The whole API.
     *
     * @param updates the run inbox, sharing one directory with the run loop
     * @param audit {@code audit_log}, for {@link ActionsApi}
     * @param online where the player counts and the player list come from, or {@code null} without a database
     * @param resolve steward-agent's resolve, asked again when the cache ages; {@code null} makes the
     *     endpoint answer 503
     * @param managedPlugins the plugin routes, or {@code null} without a database, when they answer 503
     * @param messageOverrides the admins' message overrides, or {@code null} without a database, when a save is refused
     * @param nightly the schedule as it stands right now, changed with the steward settings
     * @param settings every process's settings, or {@code null} without a database, when their routes answer 503
     */
    public StackApi(
            final AgentClient agent,
            final UpdateDirectory updates,
            final AuditDirectory audit,
            final Supplier<Nightly> nightly,
            final @Nullable ServicesApi online,
            final @Nullable Supplier<AgentWire.Resolve> resolve,
            final @Nullable PluginsForward managedPlugins,
            final @Nullable MessageOverrideStore messageOverrides,
            final @Nullable SettingStore settings,
            final Clock clock,
            final Scheduler scheduler) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.scheduler = scheduler;
        this.driftRefresh = scheduler.serial();
        this.updates = updates;
        this.managedPlugins = managedPlugins;
        this.settings = settings == null ? null : new SettingsApi(settings);
        this.agent = agent;
        this.nightly = nightly;
        this.messages = new MessagesApi(agent, messageOverrides, settings);
        // One query over two tables, not a frontend-side merge.
        this.actions = new ActionsApi(updates, audit);
        this.audit = audit;
        // Beside the drift lane, so a du never waits behind a registry call.
        this.disk = new DiskUsage(agent, scheduler, clock::instant);
        this.logFollows = new LogFollows(agent, scheduler);
        this.serviceRows = new ServiceRows(agent, updates, online);
        this.drift = new Refreshed<>(
                () -> new Drift(agent.images(), clock.instant()), DRIFT_TTL, driftRefresh, clock::instant);
        // The drift lane: both are slow calls nobody asked for.
        this.available = resolve == null ? null : new Refreshed<>(resolve, AVAILABLE_TTL, driftRefresh, clock::instant);
    }

    /** Puts every route of this API onto {@code config}, behind the gate {@code caller} answers for. */
    public void register(final JavalinConfig config, final Caller caller) {
        Routes.register(this, config, caller);
    }

    /** Reads an admin's preview of a text out of {@code POST /api/message-preview}, which the web then asks for. */
    public MessagePreview messagePreview(final Context ctx) {
        return messages.preview(ctx);
    }

    /** Registers the topics only this API can read: the service table, the host, the topology and the settings. */
    public void watch(final LiveFeed live) {
        live.watch(Topic.SERVICES, this::serviceTable);
        live.watch(Topic.HOST, this::hostNumbers);
        live.watch(Topic.TOPOLOGY, this::network);
        if (settings != null) {
            live.watch(Topic.SETTINGS, settings::read);
        }
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
            throw new RequestRefused(503, ANSWER.noDatabase(StewardTexts.Kept.SETTINGS));
        }
        return settings;
    }

    /** The plugin routes, or a 503, since "no plugins" and "cannot read the table" are different answers. */
    PluginsForward plugins() {
        if (managedPlugins == null) {
            throw new RequestRefused(503, ANSWER.noDatabase(StewardTexts.Kept.PLUGINS));
        }
        return managedPlugins;
    }

    /** {@code GET /api/services}: every row, and how old the image comparison is, which is not the age of a row. */
    public record ServiceTable(List<ServiceRows.Service> services, DriftReading drift) {}

    /**
     * How the running images compared with the registry, and when.
     *
     * @param message why the images could not be read at all, absent when they could
     */
    public record DriftReading(
            Instant checkedAt,
            boolean reached,
            List<String> unverifiable,
            @Nullable String message) {}

    /** The services the network page draws, as compose.yml's labels place and wire them, in file order. */
    NetworkMap network() {
        final List<NetworkBox> boxes = new ArrayList<>();
        for (final AgentWire.Service service : agent.topology().services()) {
            final AgentWire.Wiring wiring = service.wiring();
            if (wiring != null) {
                boxes.add(new NetworkBox(
                        service.name(), wiring.section(), wiring.entry(), wiring.reaches(), wiring.storesIn()));
            }
        }
        return new NetworkMap(boxes);
    }

    /** {@code GET /api/topology}: every service the network page draws, the one list the browser knows them by. */
    public record NetworkMap(List<NetworkBox> services) {}

    /**
     * One service as the network page draws it.
     *
     * @param section the heading it is grouped under
     * @param entry whether players reach it from outside
     * @param reaches the services it sends requests to
     * @param storesIn the services it keeps its data in
     */
    public record NetworkBox(String name, String section, boolean entry, List<String> reaches, List<String> storesIn) {}

    /** The service table, with the age of the drift comparison beside it. */
    ServiceTable serviceTable() {
        // Taken once, for the rows and for the sentence about them.
        final Drift drift = drift();
        final ImageResult images = drift.result();
        return new ServiceTable(
                serviceRows.rows(images),
                new DriftReading(
                        drift.checkedAt(),
                        images.reached(),
                        images.unverifiable().stream().sorted().toList(),
                        images.message()));
    }

    /** The drift answer as it stands, refreshed in the background rather than on the caller's thread. */
    private Drift drift() {
        return drift.get();
    }

    Optional<ServiceRows.Service> service(final String name) {
        final ImageResult drift = drift().result();
        return agent.container(name).map(container -> {
            final AgentWire.Topology topology = serviceRows.topology();
            final boolean hasPlugins = topology.hasPlugins(name);
            final Optional<DiskUsage.Measured> measured = hasPlugins ? disk.of(name) : Optional.empty();
            return ServiceRows.describe(container, drift, serviceRows.online(), serviceRows.holds(), topology)
                    .detailed(
                            container.digests() == null ? List.of() : container.digests(),
                            hasPlugins,
                            logCapacity(name),
                            measured.map(each -> each.bytes().getAsLong()).orElse(null),
                            measured.map(DiskUsage.Measured::at).orElse(null));
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
                                scheduler,
                                clock::instant))
                .get();
    }

    /**
     * {@code GET /api/schedule}: both nightly clocks as the settings say them, the zone, and their next moments.
     *
     * A blank time is no schedule, so {@code backupAt} and {@code updateAt} are absent then, and so is the next moment.
     */
    public record Schedule(
            @Nullable String backupAt,
            List<String> backupDays,
            String zone,
            @Nullable String nextBackupAt,
            @Nullable String updateAt,
            List<String> updateDays,
            @Nullable String nextUpdateAt) {}

    /** {@code backup.at}, {@code backup.days}, the zone they are read in, and the next moment. */
    Schedule schedule() {
        final Nightly nightly = this.nightly.get();
        final ZonedDateTime now = ZonedDateTime.now(clock.withZone(nightly.zone()));
        // The weekdays as the settings say them, not as the clock understood them.
        return new Schedule(
                nightly.at().isBlank() ? null : nightly.at(),
                nightly.days(),
                nightly.zone().getId(),
                NightlyClock.next(nightly.at(), nightly.days(), nightly.zone(), now)
                        .map(next -> next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .orElse(null),
                nightly.updateAt().isBlank() ? null : nightly.updateAt(),
                nightly.updateDays(),
                nightly.updateAt().isBlank()
                        ? null
                        : NightlyClock.next(
                                        NightlyClock.Job.UPDATE,
                                        nightly.updateAt(),
                                        nightly.updateDays(),
                                        nightly.zone(),
                                        now)
                                .map(next -> next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                .orElse(null));
    }

    /**
     * {@code GET /api/host}: the machine's numbers, and what Docker's images and volumes take of its disk.
     *
     * A part that could not be read is absent, with its sentence in {@code unreadable} or {@code dockerDiskUnreadable}.
     */
    public record Host(
            @Nullable Double load1,
            @Nullable Integer cpus,
            @Nullable Double cpuPercent,
            @Nullable Long memoryTotalBytes,
            @Nullable Long memoryAvailableBytes,
            @Nullable Long diskTotalBytes,
            @Nullable Long diskUsedBytes,
            @Nullable Long imagesBytes,
            @Nullable Long volumesBytes,
            @Nullable String unreadable,
            @Nullable String dockerDiskUnreadable) {}

    Host hostNumbers() {
        final AgentWire.Host host;
        try {
            host = agent.host();
        } catch (final InternalClient.Failure unreachable) {
            return new Host(
                    null, null, null, null, null, null, null, null, null, AgentClient.sentence(unreachable), null);
        }
        final AgentWire.HostNumbers numbers = host.numbers();
        final boolean docker = host.dockerDiskUnreadable() == null;
        return new Host(
                numbers == null ? null : numbers.load1(),
                numbers == null ? null : numbers.cpus(),
                numbers == null ? null : numbers.cpuPercent(),
                numbers == null ? null : numbers.memoryTotalBytes(),
                numbers == null ? null : numbers.memoryAvailableBytes(),
                numbers == null ? null : numbers.diskTotalBytes(),
                numbers == null ? null : numbers.diskUsedBytes(),
                docker ? host.imagesBytes() : null,
                docker ? host.volumesBytes() : null,
                numbers == null ? String.valueOf(host.unreadable()) : null,
                host.dockerDiskUnreadable());
    }

    /** What is actually on the disk, newest first, not what a run reported. */
    List<AgentWire.Archive> archives() {
        return agent.archives();
    }

    /**
     * Streams one finished archive from the agent to the browser as a download, never buffered.
     *
     * @return the archive as the list has it, empty when it was pruned meanwhile
     */
    Optional<AgentWire.Archive> download(final Context ctx, final String name) {
        // The agent refuses a name that is not a finished archive with a 400 and a missing one with a 404.
        final InputStream body = agent.archive(name);
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
        // The length from the list, so the browser can show progress; an archive pruned meanwhile has none.
        final Optional<AgentWire.Archive> listed = archives().stream()
                .filter(archive -> archive.name().equals(name))
                .findFirst();
        listed.ifPresent(archive -> ctx.header("Content-Length", String.valueOf(archive.bytes())));
        ctx.result(body);
        return listed;
    }

    /**
     * Asks for a restore of one finished archive, once the caller has typed what it replaces.
     *
     * The run takes a fresh backup first, counts down and stops what the archive replaces; this only writes the row.
     */
    void restore(final Context ctx, final eu.nordtal.season.common.id.Actor actor) {
        final String name = ctx.pathParam("name");
        final AgentWire.Archive archive = archives().stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noBackup(name)));
        final String replaces = archive.restoresInto();
        if (replaces == null) {
            throw new RequestRefused(400, ANSWER.backupUnfinished(name));
        }
        final Confirmation body = ctx.bodyAsClass(Confirmation.class);
        if (body == null || !replaces.equals(body.confirm)) {
            throw new RequestRefused(400, ANSWER.confirmRestore(replaces));
        }
        // A refused write reaches the error handler, which words it with the admins' overrides.
        final eu.nordtal.season.database.update.UpdateRequest written = updates.submit(
                new eu.nordtal.season.database.inbox.StewardRequest.Restore(List.of(), name), actor, Duration.ZERO);
        log.info("restore of {} asked for as request {}", name, written.id());
        ctx.status(202).json(new RestoreAsked(written.id(), written.kind(), name));
    }

    /** {@code POST /api/backups/{name}/restore}: the run that restores it, by its id. */
    public record RestoreAsked(long id, eu.nordtal.season.database.update.UpdateKind kind, String archive) {}

    /** The body of a restore: what it replaces, typed back. */
    static final class Confirmation {
        @Nullable
        String confirm;
    }

    /** What every measured alert is judged on: the service table, the archives on disk and the host's numbers. */
    public StackReading stackReading() {
        final ImageResult images = drift().result();
        final List<StackReading.Service> services = new ArrayList<>();
        for (final ServiceRows.Service row : serviceRows.rows(images)) {
            services.add(new StackReading.Service(
                    row.service(), row.state(), row.health(), row.quiet(), row.drift() == ImageResult.State.OUTDATED));
        }
        final String registryProblem = images.reached()
                ? null
                : java.util.Objects.requireNonNullElse(images.message(), "the registry did not answer");
        final List<StackReading.Archive> archives = archives().stream()
                .map(archive -> new StackReading.Archive(
                        archive.name(), archive.modified(), archive.partial(), archive.offsite()))
                .toList();
        return new StackReading(
                services,
                registryProblem,
                archives,
                host(),
                serviceRows.topology().mountedBy());
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

    /** Writes the journal line of a change a route made; the change has happened, so a failure is only logged. */
    void journal(final AuditLine line) {
        try {
            audit.record(line);
        } catch (final RuntimeException failed) {
            log.error("Could not write the journal line {} {}", line.action(), line.line(), failed);
        }
    }

    /** Types one line into a server's console through the agent, which logs who typed it beside the line. */
    void console(final String service, final String command, final eu.nordtal.season.common.id.Actor actor) {
        agent.console(service, command, actor);
    }

    /** Stops every open log follow; called before Jetty stops. */
    @Override
    public void close() {
        logFollows.close();
    }
}
