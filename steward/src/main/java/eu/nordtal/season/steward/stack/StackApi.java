package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.settings.PackState;
import eu.nordtal.season.steward.alert.StackReading;
import eu.nordtal.season.steward.backup.NightlyClock;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.live.LiveFeed;
import eu.nordtal.season.steward.live.Topic;
import eu.nordtal.season.steward.messages.DocumentsApi;
import eu.nordtal.season.steward.messages.MessagesApi;
import eu.nordtal.season.steward.settings.ServerTrial;
import eu.nordtal.season.steward.settings.SettingsApi;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.io.InputStream;
import java.net.URI;
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

/** The routes for services, logs, the console, settings, messages, the host, backups and plugins. */
public final class StackApi implements AutoCloseable {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(StackApi.class);

    final AgentClient agent;

    /**
     * The values of {@code backup.at} and {@code update.at} with their zone.
     *
     * @param at {@code HH:mm}, or blank for no nightly backup
     * @param updateAt {@code HH:mm}, or blank for no scheduled update
     * @param zone the network's default zone
     */
    public record Nightly(String at, List<String> days, String updateAt, List<String> updateDays, ZoneId zone) {

        /** Creates a schedule without a scheduled update. */
        public Nightly(final String at, final List<String> days, final ZoneId zone) {
            this(at, days, "", List.of(), zone);
        }
    }

    private final Supplier<Nightly> nightly;

    final PluginsForward plugins;

    final SettingsApi settings;

    final LogFollows logFollows;

    private final ServiceRows serviceRows;

    private static final Duration DRIFT_TTL = Duration.ofMinutes(1);
    /** How long the answer of the proxy's pack URL stays cached. */
    private static final Duration PACK_TTL = Duration.ofMinutes(10);

    private static final WebClient PACK_PROBE =
            WebClient.create(Duration.ofSeconds(10)).followingRedirects();

    /** The largest console step in lines. */
    static final int LOG_CAPACITY_MAX = 10_000;

    private static final Duration LOG_CAPACITY_TTL = Duration.ofMinutes(5);

    /** One image comparison with its time. */
    private record Drift(ImageResult result, Instant checkedAt) {}

    /** Runs background refreshes one at a time. */
    private final Executor driftRefresh;

    private final Refreshed<Drift> drift;

    /** The pack URL of the proxy when it does not answer. */
    private final Refreshed<Optional<String>> deadPack;

    private static final Duration AVAILABLE_TTL = Duration.ofHours(6);

    /** The resolve, or {@code null} without sources, which makes the endpoint answer 503. */
    final @Nullable Refreshed<AgentWire.Resolve> available;

    final MessagesApi messages;
    final DocumentsApi documents;
    private final DiskUsage disk;
    /** The console line count per service, capped at {@code LOG_CAPACITY_MAX}. */
    private final Map<String, Refreshed<Integer>> logCapacity = new ConcurrentHashMap<>();

    private final Clock clock;
    private final Scheduler scheduler;

    private final UpdateDirectory updates;

    /**
     * Creates the whole API.
     *
     * @param updates the run inbox
     * @param online the player counts and the player list
     * @param resolve steward-agent's resolve, or {@code null}, which makes the endpoint answer 503
     * @param messageOverrides the message overrides
     * @param documentStore the admins' documents and images
     * @param nightly the current schedule
     * @param settings every process's settings
     * @param trial asks a server whether it takes a settings change before it is saved
     */
    public StackApi(
            final AgentClient agent,
            final UpdateDirectory updates,
            final Supplier<Nightly> nightly,
            final ServicesApi online,
            final @Nullable Supplier<AgentWire.Resolve> resolve,
            final PluginsForward plugins,
            final MessageOverrideStore messageOverrides,
            final DocumentStore documentStore,
            final SettingStore settings,
            final ServerTrial trial,
            final Clock clock,
            final Scheduler scheduler) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.scheduler = scheduler;
        this.driftRefresh = scheduler.serial();
        this.updates = updates;
        this.plugins = plugins;
        this.settings = new SettingsApi(settings, agent, messageOverrides, trial);
        this.agent = agent;
        this.nightly = nightly;
        this.messages = new MessagesApi(agent, messageOverrides, settings);
        this.documents = new DocumentsApi(agent, documentStore, settings);
        this.disk = new DiskUsage(agent, scheduler, clock::instant);
        this.logFollows = new LogFollows(agent, scheduler);
        this.serviceRows = new ServiceRows(agent, updates, online);
        this.drift = new Refreshed<>(
                () -> new Drift(agent.images(), clock.instant()), DRIFT_TTL, driftRefresh, clock::instant);
        this.available = resolve == null ? null : new Refreshed<>(resolve, AVAILABLE_TTL, driftRefresh, clock::instant);
        this.deadPack = new Refreshed<>(() -> deadPack(settings), PACK_TTL, driftRefresh, clock::instant);
    }

    public void register(final JavalinConfig config, final Caller caller) {
        Routes.register(this, config, caller);
    }

    /** Returns the preview of {@code POST /api/message-preview}. */
    public MessagePreview messagePreview(final Context ctx) {
        return messages.preview(ctx);
    }

    /** Returns the preview of {@code POST /api/document-preview}. */
    public DocumentPreview documentPreview(final Context ctx) {
        return documents.preview(ctx);
    }

    /** Registers the topics for the service table, the host, the topology and the settings. */
    public void watch(final LiveFeed live) {
        live.watch(Topic.SERVICES, this::serviceTable);
        live.watch(Topic.HOST, this::hostNumbers);
        live.watch(Topic.TOPOLOGY, this::network);
        live.watch(Topic.SETTINGS, settings::read);
    }

    public boolean agentReachable() {
        return agent.isReachable();
    }

    /** Runs the first image comparison in the background. */
    public void warm() {
        driftRefresh.execute(() -> {
            try {
                drift.get();
            } catch (RuntimeException failed) {
                log.warn("the first image comparison failed - the next request will try again: {}", failed.toString());
            }
        });
    }

    /** The answer to {@code GET /api/services}: every row and the age of the image comparison. */
    public record ServiceTable(List<ServiceRows.Service> services, DriftReading drift) {}

    /**
     * The comparison of the running images with the registry.
     *
     * @param message the reason the images could not be read, absent when they could
     */
    public record DriftReading(
            Instant checkedAt,
            boolean reached,
            List<String> unverifiable,
            @Nullable String message) {}

    /** Returns the services of the network page from the labels of compose.yml, in file order. */
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

    /** The answer to {@code GET /api/topology}. */
    public record NetworkMap(List<NetworkBox> services) {}

    /**
     * One service of the network page.
     *
     * @param section the heading of its group
     * @param entry whether players reach it from outside
     * @param reaches the services it sends requests to
     * @param storesIn the services that store its data
     */
    public record NetworkBox(String name, String section, boolean entry, List<String> reaches, List<String> storesIn) {}

    ServiceTable serviceTable() {
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

    private Drift drift() {
        return drift.get();
    }

    Optional<ServiceRows.Service> service(final String name) {
        final ImageResult drift = drift().result();
        return agent.container(name).map(container -> {
            final AgentWire.Topology topology = serviceRows.topology();
            final boolean hasPlugins = topology.hasPlugins(name);
            final Optional<DiskUsage.Measured> measured = hasPlugins ? disk.of(name) : Optional.empty();
            return ServiceRows.describe(
                            container, drift, serviceRows.online(), serviceRows.holds(), serviceRows.moving(), topology)
                    .detailed(
                            container.digests() == null ? List.of() : container.digests(),
                            hasPlugins,
                            logCapacity(name),
                            measured.map(each -> each.bytes().getAsLong()).orElse(null),
                            measured.map(DiskUsage.Measured::at).orElse(null));
        });
    }

    /** Returns the console line count, which the agent is asked for at most every five minutes. */
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
     * The answer to {@code GET /api/schedule}.
     * A blank time leaves {@code backupAt} or {@code updateAt} and its next moment absent.
     */
    public record Schedule(
            @Nullable String backupAt,
            List<String> backupDays,
            String zone,
            @Nullable String nextBackupAt,
            @Nullable String updateAt,
            List<String> updateDays,
            @Nullable String nextUpdateAt) {}

    Schedule schedule() {
        final Nightly nightly = this.nightly.get();
        final ZonedDateTime now = ZonedDateTime.now(clock.withZone(nightly.zone()));
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
     * The answer to {@code GET /api/host}.
     * A part that could not be read is absent, and {@code unreadable} or {@code dockerDiskUnreadable} says why.
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

    List<AgentWire.Archive> archives() {
        return agent.archives();
    }

    /**
     * Streams one finished archive from the agent to the browser as a download.
     *
     * @return the archive as the list has it, empty when it was pruned meanwhile
     */
    Optional<AgentWire.Archive> download(final Context ctx, final String name) {
        final InputStream body = agent.archive(name);
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
        // An archive pruned meanwhile has no length.
        final Optional<AgentWire.Archive> listed = archives().stream()
                .filter(archive -> archive.name().equals(name))
                .findFirst();
        listed.ifPresent(archive -> ctx.header("Content-Length", String.valueOf(archive.bytes())));
        ctx.result(body);
        return listed;
    }

    /** Writes the restore request for one finished archive after the caller has typed what it replaces. */
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
        final eu.nordtal.season.database.update.UpdateRequest written = updates.submit(
                new eu.nordtal.season.database.inbox.StewardRequest.Restore(name), List.of(), actor, Duration.ZERO);
        log.info("restore of {} asked for as request {}", name, written.id());
        Journaling.line(ctx, new JournalDetail.RestoreAsked(written.id(), name));
        ctx.status(202).json(new RestoreAsked(written.id(), written.kind(), name));
    }

    /** The answer to {@code POST /api/backups/{name}/restore}. */
    public record RestoreAsked(long id, eu.nordtal.season.database.update.UpdateKind kind, String archive) {}

    static final class Confirmation {
        @Nullable
        String confirm;
    }

    /** Returns the service table, the archives and the host numbers that the alerts read. */
    public StackReading stackReading() {
        final ImageResult images = drift().result();
        final List<StackReading.Service> services = serviceRows.readings(images);
        final String registryProblem = images.reached()
                ? null
                : java.util.Objects.requireNonNullElse(images.message(), "the registry did not answer");
        final AgentWire.Topology topology = serviceRows.topology();
        final List<StackReading.Archive> archives = archives().stream()
                .map(archive -> new StackReading.Archive(
                        archive.name(), archive.modified(), archive.partial(), archive.offsite()))
                .toList();
        return new StackReading(
                services,
                registryProblem,
                archives,
                host(),
                topology.backupVolumes(),
                topology.mountedBy(),
                deadPack.get().orElse(null));
    }

    /** Returns the pack URL when a GET of it does not answer 200, else empty. */
    private static Optional<String> deadPack(final SettingStore settings) {
        final String url = PackState.read(settings).url();
        if (url == null) {
            return Optional.empty();
        }
        try {
            return PACK_PROBE.answers(URI.create(url)) ? Optional.empty() : Optional.of(url);
        } catch (final IllegalArgumentException notAUrl) {
            return Optional.of(url);
        }
    }

    /** Returns the disk and memory numbers, or {@code null} when the agent cannot read them. */
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

    static final class ConsoleLine {
        @Nullable
        String command;
    }

    /** Types one line into a server's console through the agent. */
    void console(final String service, final String command, final eu.nordtal.season.common.id.Actor actor) {
        agent.console(service, command, actor);
    }

    @Override
    public void close() {
        logFollows.close();
    }
}
