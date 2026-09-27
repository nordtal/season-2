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
 * Builds the rows of {@code /api/services} and {@code /api/services/{name}}.
 *
 * Docker's stats call takes about a second per container, so rows are read in parallel on virtual threads.
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

    /** Every service row, sorted by name, read in parallel. */
    List<Map<String, Object>> rows(final ImageResult drift) {
        final List<Docker.Container> containers = docker.containers(project).stream()
                .filter(container -> container.service() != null)
                .toList();
        // Once for the whole table, so two rows cannot disagree about the same instant.
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

    /** {@code update_request} and {@code service_hold}, taken once for the whole table. */
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
            // describe() turns a Docker failure into `unreadable`, so anything here is a programming error.
            throw new IllegalStateException("reading one service failed", e.getCause());
        }
    }

    /**
     * One row.
     *
     * {@code players} and {@code roster} are absent, not zero or empty, for a service the proxy has not reported.
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
        // Same rule as `players`: the key is absent when nobody holds it.
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

    /** The counts and the list as they stand, or nothing at all, never a guessed zero. */
    ServicesApi.Online online() {
        return players == null ? ServicesApi.Online.NONE : players.read();
    }

    /**
     * Marks the row of a standby service, whose normal state is stopped, so a dashboard does not call it down.
     *
     * @param service the compose service name this row is about
     */
    static void putStandby(final Map<String, Object> row, final String service) {
        if (Topology.standbyNames().contains(service)) {
            row.put("standby", true);
        }
    }

    /**
     * Writes {@code players} and {@code roster} onto a row, or neither.
     *
     * @param service the compose service name both maps are keyed by
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

    /** The two fields of a player that leave this process. */
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
