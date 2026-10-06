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

/**
 * Builds the rows of {@code /api/services} and {@code /api/services/{name}}.
 *
 * Memory and CPU are the agent's last sample, so drawing the table asks Docker for nothing per container.
 */
public final class ServiceRows {

    /** How long after a run settles the services it moved are still its, so the next reading sees them back. */
    static final Duration AFTER_RUN = Duration.ofMinutes(2);

    private final AgentClient agent;
    private final UpdateDirectory updates;
    private final @Nullable ServicesApi players;

    ServiceRows(final AgentClient agent, final UpdateDirectory updates, final @Nullable ServicesApi players) {
        this.agent = agent;
        this.updates = updates;
        this.players = players;
    }

    /**
     * One row of {@code /api/services}; the last five fields only on {@code /api/services/{name}}.
     *
     * Absent is never zero, empty or false: nobody reported the players, or the service is no standby and not held.
     * @param localBuild the image or jars it runs that were built on the host, which an update asks before replacing
     * @param oneShot true for a service that runs once and exits, absent for every other
     * @param lastRun how a one-shot's last run went, absent while it runs, before it ran and for every other service
     * @param alert {@code DOWN} where {@link StackAlerts#down} finds the row red, else absent
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
            @Nullable Long memoryBytes,
            @Nullable Long memoryLimitBytes,
            @Nullable Double cpuPercent,
            @Nullable List<String> digests,
            @Nullable Boolean hasPlugins,
            @Nullable Integer logCapacity,
            @Nullable Long diskBytes,
            @Nullable Instant diskMeasuredAt) {

        /** What the alerts read of this row, with the services a run is moving. */
        StackReading.Service reading(final Set<String> moving) {
            return ServiceRows.reading(
                    service,
                    state,
                    health,
                    purpose(service, standby, hold, oneShot, moving),
                    lastRun == null ? null : lastRun.exitCode(),
                    drift == ImageResult.State.OUTDATED);
        }

        /** This row with what only the page of one service shows. */
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

    /** What a service is for right now: a run moving it outranks everything, then a one-shot, then a meant stop. */
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
            final boolean outdated) {
        return new StackReading.Service(service, state, health, purpose, exitCode, outdated);
    }

    /** Somebody stopped the service on purpose, and it stays stopped. */
    public record Hold(Instant since) {}

    /**
     * How a one-shot's last run went.
     *
     * @param startedAt when it started, or {@code null} when Docker did not say
     * @param finishedAt when it ended
     */
    public record LastRun(@Nullable String startedAt, String finishedAt, int exitCode) {}

    /** The two fields of a connected player that leave this process. */
    public record Connected(String uuid, String name) {}

    /** Every service row, sorted by name. */
    List<Service> rows(final ImageResult drift) {
        return table(drift).rows();
    }

    /** What the alerts read of every service, from the same look at the stack the rows come from. */
    public List<StackReading.Service> readings(final ImageResult drift) {
        final Table table = table(drift);
        return table.rows().stream().map(row -> row.reading(table.moving())).toList();
    }

    /** The rows and the services the runs were moving when they were read. */
    private record Table(List<Service> rows, Set<String> moving) {}

    private Table table(final ImageResult drift) {
        final AgentWire.Containers containers = agent.containers();
        if (!containers.reached()) {
            // The agent answered and the daemon behind it did not; the interface names the daemon.
            throw InternalClient.Failure.behind("docker", String.valueOf(containers.message()));
        }
        // Once for the whole table, so two rows cannot disagree about the same instant.
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

    /** The services the open run is moving, and those a run that settled within {@link #AFTER_RUN} moved. */
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

    /** What compose.yml's labels say, taken once for the whole table. */
    AgentWire.Topology topology() {
        return agent.topology();
    }

    /** The services compose.yml gives a console. */
    static Set<String> consoles(final AgentWire.Topology topology) {
        final Set<String> consoles = new LinkedHashSet<>();
        for (final AgentWire.Service service : topology.services()) {
            if (service.console()) {
                consoles.add(service.name());
            }
        }
        return consoles;
    }

    /** The run inbox and {@code service_hold}, taken once for the whole table. */
    Map<String, ServiceHold> holds() {
        final Map<String, ServiceHold> holds = new LinkedHashMap<>();
        for (final ServiceHold hold : updates.holds()) {
            holds.put(hold.service(), hold);
        }
        return holds;
    }

    /** One row. */
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
                false));
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
                sample == null ? null : sample.memoryBytes(),
                sample == null ? null : sample.memoryLimitBytes(),
                sample == null ? null : sample.cpuPercent(),
                null,
                null,
                null,
                null,
                null);
    }

    /** How a container's last run ended, or nothing while it runs or before it ever ended. */
    private static @Nullable LastRun lastRun(final AgentWire.Container container) {
        final String finishedAt = container.finishedAt();
        final Integer exitCode = container.exitCode();
        if (container.isRunning() || finishedAt == null || exitCode == null) {
            return null;
        }
        return new LastRun(container.startedAt(), finishedAt, exitCode);
    }

    /** The counts and the list as they stand, or nothing at all, never a guessed zero. */
    ServicesApi.Online online() {
        return players == null ? ServicesApi.Online.NONE : players.read();
    }

    /**
     * True for a standby service, whose normal state is stopped, so a dashboard does not call it down.
     *
     * Absent rather than false for every other service, so there is one spelling.
     */
    static @Nullable Boolean standby(final String service, final AgentWire.Topology topology) {
        return topology.standbys().contains(service) ? Boolean.TRUE : null;
    }

    /** Who is connected to a service, or nothing at all when nobody has said, never a guessed empty list. */
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
