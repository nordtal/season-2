package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.updater.arcane.ArcaneOps;
import eu.nordtal.s2.updater.arcane.RedeployResult;
import eu.nordtal.s2.updater.arcane.RuntimeResult;
import eu.nordtal.s2.updater.arcane.ServiceRuntime;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Arcane that answers from a map instead of over HTTP.
 *
 * <p>Everything the update sequence does is an ordering decision, and the only way to see one is to
 * record the calls. {@link #calls} is that record, in order: {@code stop:smp}, {@code start:smp}.</p>
 */
final class FakeArcane implements ArcaneOps {

    /** Every stop and start, in the order they were asked for. */
    final List<String> calls = new ArrayList<>();

    /** service -> its current runtime. Mutated by the test to make a server come back, or not. */
    private final Map<String, ServiceRuntime> services = new LinkedHashMap<>();

    private boolean reachable = true;
    private boolean stopFails;

    FakeArcane running(final String... names) {
        for (final String name : names) {
            services.put(name, new ServiceRuntime(name, name + "-container", "running", "healthy"));
        }
        return this;
    }

    /** Arcane is not answering at all - the case the whole run must refuse to start on. */
    FakeArcane unreachable() {
        reachable = false;
        return this;
    }

    /** Every stop is refused, so nothing may be installed and nothing may be started. */
    FakeArcane stopFails() {
        stopFails = true;
        return this;
    }

    /** A service that is up but whose plugin died - running, unhealthy. The interesting failure. */
    void sick(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running",
                "unhealthy"));
    }

    void back(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running",
                "healthy"));
    }

    @Override
    public @NotNull RuntimeResult runtime() {
        return reachable ? RuntimeResult.of(List.copyOf(services.values()))
                : RuntimeResult.unreachable("Arcane is not answering");
    }

    @Override
    public @NotNull RedeployResult stop(final @NotNull String containerId) {
        calls.add("stop:" + containerId);
        if (stopFails) {
            return RedeployResult.refused("refused");
        }
        services.computeIfPresent(service(containerId), (name, entry) ->
                new ServiceRuntime(name, entry.containerId(), "exited", null));
        return RedeployResult.triggered("HTTP 204");
    }

    @Override
    public @NotNull RedeployResult start(final @NotNull String containerId) {
        calls.add("start:" + containerId);
        // Started, and NOT healthy: that is the whole distinction the verify step exists for.
        services.computeIfPresent(service(containerId), (name, entry) ->
                new ServiceRuntime(name, entry.containerId(), "running", "starting"));
        return RedeployResult.triggered("HTTP 204");
    }

    private static String service(final String containerId) {
        return containerId.endsWith("-container")
                ? containerId.substring(0, containerId.length() - "-container".length())
                : containerId;
    }
}
