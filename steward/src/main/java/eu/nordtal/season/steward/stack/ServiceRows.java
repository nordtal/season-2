package eu.nordtal.season.steward.stack;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.database.update.ServiceHold;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.steward.alert.StackAlerts;
import eu.nordtal.season.steward.alert.StackReading;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds the rows of {@code /api/services} and {@code /api/services/{name}}. */
public final class ServiceRows {

    /** The time after a run finishes during which its services still count as moving. */
    static final Duration AFTER_RUN = Duration.ofMinutes(2);

    private final AgentClient agent;
    private final UpdateDirectory updates;
    private final ServicesApi players;

    ServiceRows(final AgentClient agent, final UpdateDirectory updates, final ServicesApi players) {
        this.agent = agent;
        this.updates = updates;
        this.players = players;
    }

    /**
     * One row of {@code /api/services}. Only {@code /api/services/{name}} fills the last five fields.
     *
     * @param localBuild the image or jars built on the host
     * @param oneShot true for a service that runs once and exits, else absent
     * @param lastRun the last run of a one-shot, absent while it runs, before its first run and for other services
     * @param alert {@code DOWN} where {@link StackAlerts#down} finds the row down, else absent
     * @param restarts how often Docker restarted it since it was last started on purpose, absent for none
     */
    public record Service(
            String service,
            String containerId,
            @Nullable String image,
            String state,
            @Nullable String status,
            boolean hasConsole,
            ImageResult.State drift,
            ImageResult.@Nullable LocalBuild localBuild,
            @Nullable Integer players,
            @Nullable List<Connected> roster,
            @Nullable Boolean standby,
            @Nullable Hold hold,
            @Nullable Boolean oneShot,
            @Nullable LastRun lastRun,
            @Nullable String health,
            Alert.@Nullable Level alert,
            @Nullable String startedAt,
            @Nullable Integer restarts,
            @Nullable Long memoryBytes,
            @Nullable Long memoryLimitBytes,
            @Nullable Double cpuPercent,
            @Nullable List<String> digests,
            @Nullable Boolean hasPlugins,
            @Nullable Integer logCapacity,
            @Nullable Long diskBytes,
            @Nullable Instant diskMeasuredAt) {

        /** Returns the reading of this row for the alerts. */
        StackReading.Service reading(final Set<String> moving) {
            return ServiceRows.reading(
                    service,
                    state,
                    health,
                    purpose(service, standby, hold, oneShot, moving),
                    lastRun == null ? null : lastRun.exitCode(),
                    drift == ImageResult.State.OUTDATED,
                    restarts == null ? 0 : restarts,
                    startedAt == null ? null : Instant.parse(startedAt));
        }

        /** Returns this row with the fields of the service page. */
        Service detailed(
                final List<String> digests,
                final boolean hasPlugins,
                final int logCapacity,
                final @Nullable Long diskBytes,
                final @Nullable Instant diskMeasuredAt) {
            return new Service(
                    service,
                    containerId,
                    image,
                    state,
                    status,
                    hasConsole,
                    drift,
                    localBuild,
                    players,
                    roster,
                    standby,
                    hold,
                    oneShot,
                    lastRun,
                    health,
                    alert,
                    startedAt,
                    restarts,
                    memoryBytes,
                    memoryLimitBytes,
                    cpuPercent,
                    digests,
                    hasPlugins,
                    logCapacity,
                    diskBytes,
                    diskMeasuredAt);
        }
    }

    /** Returns the purpose of a service. {@code MOVING} wins over {@code ONCE}, and {@code ONCE} over the rest. */
    static StackReading.Purpose purpose(
            final String service,
            final @Nullable Boolean standby,
            final @Nullable Object hold,
            final @Nullable Boolean oneShot,
            final Set<String> moving) {
        if (moving.contains(service)) {
            return StackReading.Purpose.MOVING;
        }
        if (Boolean.TRUE.equals(oneShot)) {
            return StackReading.Purpose.ONCE;
        }
        return Boolean.TRUE.equals(standby) || hold != null ? StackReading.Purpose.RESTS : StackReading.Purpose.SERVES;
    }

    private static StackReading.Service reading(
            final String service,
            final String state,
            final @Nullable String health,
            final StackReading.Purpose purpose,
            final @Nullable Integer exitCode,
            final boolean outdated,
            final int restarts,
            final @Nullable Instant startedAt) {
        return new StackReading.Service(service, state, health, purpose, exitCode, outdated, restarts, startedAt);
    }

    public record Hold(Instant since) {}

    /**
     * The last run of a one-shot.
     *
     * @param startedAt the start time, or {@code null} when Docker gives none
     * @param finishedAt the end time
     */
    public record LastRun(@Nullable String startedAt, String finishedAt, int exitCode) {}

    public record Connected(String uuid, String name) {}

    List<Service> rows(final ImageResult drift) {
        return table(drift).rows();
    }

    /** Returns the reading of every service for the alerts. */
    public List<StackReading.Service> readings(final ImageResult drift) {
        final Table table = table(drift);
        return table.rows().stream().map(row -> row.reading(table.moving())).toList();
    }

    private record Table(List<Service> rows, Set<String> moving) {}

    private Table table(final ImageResult drift) {
        final AgentWire.Containers containers = agent.containers();
        if (!containers.reached()) {
            throw InternalClient.Failure.behind("docker", String.valueOf(containers.message()));
        }
        final ServicesApi.Online counts = online();
        final Map<String, ServiceHold> holds = holds();
        final Set<String> moving = moving();
        final AgentWire.Topology topology = topology();
        final List<Service> all = new ArrayList<>();
        for (final AgentWire.Container container : containers.containers()) {
            all.add(describe(container, drift, counts, holds, moving, topology));
        }
        return new Table(List.copyOf(all), Set.copyOf(moving));
    }

    /** Returns the services of the open run and of runs finished within {@link #AFTER_RUN}. */
    Set<String> moving() {
        return moving(updates);
    }

    static Set<String> moving(final UpdateDirectory updates) {
        final Set<String> moving = new LinkedHashSet<>();
        updates.open()
                .filter(run -> run.status() == UpdateStatus.RUNNING)
                .ifPresent(run -> moving.addAll(run.moving()));
        for (final UpdateRequest run : updates.finishedWithin(AFTER_RUN)) {
            moving.addAll(run.moving());
        }
        return moving;
    }

    AgentWire.Topology topology() {
        return agent.topology();
    }

    static Set<String> consoles(final AgentWire.Topology topology) {
        final Set<String> consoles = new LinkedHashSet<>();
        for (final AgentWire.Service service : topology.services()) {
            if (service.console()) {
                consoles.add(service.name());
            }
        }
        return consoles;
    }

    /** Returns the holds from the run inbox and {@code service_hold}. */
    Map<String, ServiceHold> holds() {
        final Map<String, ServiceHold> holds = new LinkedHashMap<>();
        for (final ServiceHold hold : updates.holds()) {
            holds.put(hold.service(), hold);
        }
        return holds;
    }

    static Service describe(
            final AgentWire.Container container,
            final ImageResult drift,
            final ServicesApi.Online counts,
            final Map<String, ServiceHold> holds,
            final Set<String> moving,
            final AgentWire.Topology topology) {
        final String service = container.service();
        final ServiceHold hold = holds.get(service);
        final boolean running = container.isRunning();
        final AgentWire.@Nullable Reading sample = running ? container.sample() : null;
        final @Nullable Boolean standby = standby(service, topology);
        final @Nullable Boolean oneShot = topology.oneShots().contains(service) ? Boolean.TRUE : null;
        final @Nullable LastRun lastRun = oneShot == null ? null : lastRun(container);
        final @Nullable String health = running ? container.health() : null;
        final boolean down = StackAlerts.down(reading(
                service,
                container.state(),
                health,
                purpose(service, standby, hold, oneShot, moving),
                lastRun == null ? null : lastRun.exitCode(),
                false,
                0,
                null));
        return new Service(
                service,
                container.id(),
                container.image(),
                container.state(),
                container.status(),
                consoles(topology).contains(service),
                drift.state(service),
                drift.localBuilds().get(service),
                counts.counts().get(service),
                roster(service, counts),
                standby,
                hold == null ? null : new Hold(hold.since()),
                oneShot,
                lastRun,
                health,
                down ? Alert.Level.DOWN : null,
                running ? container.startedAt() : null,
                container.restarts() > 0 ? container.restarts() : null,
                sample == null ? null : sample.memoryBytes(),
                sample == null ? null : sample.memoryLimitBytes(),
                sample == null ? null : sample.cpuPercent(),
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns the last run of a container, or {@code null} while it runs or before its first end. */
    private static @Nullable LastRun lastRun(final AgentWire.Container container) {
        final String finishedAt = container.finishedAt();
        final Integer exitCode = container.exitCode();
        if (container.isRunning() || finishedAt == null || exitCode == null) {
            return null;
        }
        return new LastRun(container.startedAt(), finishedAt, exitCode);
    }

    /** Returns the player counts and lists, or {@code Online.NONE} without a database. */
    ServicesApi.Online online() {
        return players.read();
    }

    /** Returns true for a standby service and {@code null} for every other. */
    static @Nullable Boolean standby(final String service, final AgentWire.Topology topology) {
        return topology.standbys().contains(service) ? Boolean.TRUE : null;
    }

    /** Returns the players connected to a service, or {@code null} when none are reported. */
    static @Nullable List<Connected> roster(final String service, final ServicesApi.Online online) {
        final List<OnlinePlayer> roster = online.roster().get(service);
        if (roster == null) {
            return null;
        }
        return roster.stream()
                .map(player -> new Connected(player.uuid().toString(), player.name()))
                .toList();
    }
}
