package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.online.OnlinePlayer;
import eu.nordtal.s2.common.update.ServiceHold;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;

/**
 * Builds one row of {@code /api/services} per running container, and the single row of {@code /api/services/{name}}.
 *
 * The two measurements that made this worth writing carefully: {@code /containers/{id}/stats?stream=false} takes
 * about a second per container, because the daemon collects two samples to compute a CPU delta and there is no
 * one-shot call that still yields a real percentage. Nine running containers read one after another is a
 * nine-second request - slower than most of what it is describing. So the rows are read in parallel, one virtual
 * thread each, and the table costs about as long as its slowest row.
 */
final class ServiceRows {

    private final Docker docker;
    private final String project;
    private final UpdateDirectory updates;
    private final @Nullable ServicesApi players;

    ServiceRows(
            final Docker docker,
            final String project,
            final UpdateDirectory updates,
            final @Nullable ServicesApi players) {
        this.docker = docker;
        this.project = project;
        this.updates = updates;
        this.players = players;
    }

    /** Every service row, sorted by name, read in parallel because each one costs about a second of Docker's time. */
    List<Map<String, Object>> rows(final ImageResult drift) {
        final List<Docker.Container> containers = docker.containers(project).stream()
                .filter(container -> container.service() != null)
                .toList();
        // Once for the whole table, not once per row: two reads could let two rows disagree about the same instant.
        final ServicesApi.Online counts = online();
        final Map<String, ServiceHold> holds = holds();
        final List<Map<String, Object>> all;
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            all = scope
                    .invokeAll(containers.stream()
                            .map(container ->
                                    (Callable<Map<String, Object>>) () -> describe(container, drift, counts, holds))
                            .toList())
                    .stream()
                    .map(ServiceRows::resultOf)
                    .toList();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading the service table was interrupted", e);
        }
        return all.stream()
                .sorted((left, right) ->
                        String.valueOf(left.get("service")).compareTo(String.valueOf(right.get("service"))))
                .toList();
    }

    /** {@code update_request} and {@code service_hold}, taken once for the whole table rather than once per row. */
    Map<String, ServiceHold> holds() {
        final Map<String, ServiceHold> holds = new LinkedHashMap<>();
        for (final ServiceHold hold : updates.holds()) {
            holds.put(hold.service(), hold);
        }
        return holds;
    }

    private static Map<String, Object> resultOf(final Future<Map<String, Object>> future) {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading one service was interrupted", e);
        } catch (final ExecutionException e) {
            // describe() swallows a Docker failure into `unreadable`; anything here is a programming error.
            throw new IllegalStateException("reading one service failed", e.getCause());
        }
    }

    /**
     * One row.
     *
     * {@code players} is written only for a service {@code counts} actually names. A service it does not name has
     * NO {@code players} key at all - not {@code 0} and not {@code null} - because "nobody is connected" and "the
     * proxy has not said" are different answers and a dashboard that draws the second as the first is the failure
     * {@code ImageResult.State.UNKNOWN} already exists to prevent.
     *
     * {@code roster} follows the same rule one step further: it appears only for a service that has fresh players,
     * it is never an empty array, and it is never sent for a service nobody is on. It enriches {@code players} and
     * never contradicts it - both come out of one {@link ServicesApi#read()} taken once for the whole response, so
     * no two rows of one answer can disagree about the same instant.
     */
    Map<String, Object> describe(
            final Docker.Container container,
            final ImageResult drift,
            final ServicesApi.Online counts,
            final Map<String, ServiceHold> holds) {
        final String service =
                Objects.requireNonNull(container.service(), "a row is only ever built for a compose service");
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("containerId", container.id());
        row.put("image", container.image());
        row.put("state", container.state());
        row.put("status", container.status());
        row.put("hasConsole", Console.has(service));
        row.put("drift", drift.state(service).name());
        putOnline(row, service, counts);
        putStandby(row, service);
        // Same rule as `players`: the key is absent when nobody holds it, not present and false.
        final ServiceHold hold = holds.get(service);
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
            } catch (final DockerException e) {
                // One container that will not answer is one row with less in it, never a page that fails to draw.
                row.put("unreadable", e.getMessage());
            }
        }
        return row;
    }

    /** The counts and the list as they stand, or nothing at all - never a guessed zero. */
    ServicesApi.Online online() {
        return players == null ? ServicesApi.Online.NONE : players.read();
    }

    /**
     * Marks the row of a service whose normal state is stopped.
     *
     * A standby is not down, it is off. {@code proxy-standby} and {@code limbo-standby} live in the {@code standby}
     * compose profile and are stopped for all but a minute of the season, so a dashboard that reads "not running"
     * as a fault reports two faults on a perfectly healthy stack - every day, which is precisely how a fault
     * counter stops being read and how the third fault goes unnoticed.
     *
     * It has to be said here because it cannot be seen anywhere else: to Docker a stopped standby and a crashed
     * backend are the same container state, and the frontend has nothing but the name to go on.
     * {@link Topology#standbyNames()} is the only thing that knows, and this is the one place it is asked.
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
     * {@code updated} stays behind because it has already been used - {@link ServicesApi} spent it deciding
     * whether this player is worth sending at all. {@code subject} stays behind because the row it is sitting in
     * already is that subject.
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
}
