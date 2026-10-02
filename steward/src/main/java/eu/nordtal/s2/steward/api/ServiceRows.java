package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.database.online.OnlinePlayer;
import eu.nordtal.s2.database.update.ServiceHold;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
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
final class ServiceRows {

    private final AgentClient agent;
    private final UpdateDirectory updates;
    private final @Nullable ServicesApi players;

    ServiceRows(final AgentClient agent, final UpdateDirectory updates, final @Nullable ServicesApi players) {
        this.agent = agent;
        this.updates = updates;
        this.players = players;
    }

    /** Every service row, sorted by name. */
    List<Map<String, Object>> rows(final ImageResult drift) {
        final AgentWire.Containers containers = agent.containers();
        if (!containers.reached()) {
            // The agent answered and the daemon behind it did not; the interface names the daemon.
            throw InternalClient.Failure.behind("docker", String.valueOf(containers.message()));
        }
        // Once for the whole table, so two rows cannot disagree about the same instant.
        final ServicesApi.Online counts = online();
        final Map<String, ServiceHold> holds = holds();
        final AgentWire.Topology topology = topology();
        final List<Map<String, Object>> all = new ArrayList<>();
        for (final AgentWire.Container container : containers.containers()) {
            all.add(describe(container, drift, counts, holds, topology));
        }
        return List.copyOf(all);
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

    /**
     * One row.
     *
     * {@code players} and {@code roster} are absent, not zero or empty, for a service the proxy has not reported.
     */
    Map<String, Object> describe(
            final AgentWire.Container container,
            final ImageResult drift,
            final ServicesApi.Online counts,
            final Map<String, ServiceHold> holds,
            final AgentWire.Topology topology) {
        final String service = container.service();
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("containerId", container.id());
        row.put("image", container.image());
        row.put("state", container.state());
        row.put("status", container.status());
        row.put("hasConsole", consoles(topology).contains(service));
        row.put("drift", drift.state(service).name());
        putOnline(row, service, counts);
        putStandby(row, service, topology);
        // Same rule as `players`: the key is absent when nobody holds it.
        final ServiceHold hold = holds.get(service);
        if (hold != null) {
            final Map<String, Object> about = new LinkedHashMap<>();
            about.put("since", hold.since().toString());
            row.put("hold", about);
        }
        if (container.isRunning()) {
            row.put("health", container.health());
            row.put("startedAt", container.startedAt());
            final AgentWire.Reading sample = container.sample();
            if (sample != null) {
                row.put("memoryBytes", sample.memoryBytes());
                row.put("memoryLimitBytes", sample.memoryLimitBytes());
                if (sample.cpuPercent() != null) {
                    row.put("cpuPercent", sample.cpuPercent());
                }
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
    static void putStandby(final Map<String, Object> row, final String service, final AgentWire.Topology topology) {
        if (topology.standbys().contains(service)) {
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
