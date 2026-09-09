package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.updater.arcane.ArcaneOps;
import eu.nordtal.s2.updater.arcane.BackupResult;
import eu.nordtal.s2.updater.arcane.ImageResult;
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
 * record the calls. {@link #calls} is that record, in order: {@code stop:smp}, {@code backup:mc-smp},
 * {@code start:smp}. A backup run's whole correctness is that middle entry sitting between the
 * other two - a snapshot taken of a server that is still running fails at RESTORE and nowhere
 * else.</p>
 */
final class FakeArcane implements ArcaneOps {

    /** Every stop and start, in the order they were asked for. */
    final List<String> calls = new ArrayList<>();

    /** service -> its current runtime. Mutated by the test to make a server come back, or not. */
    private final Map<String, ServiceRuntime> services = new LinkedHashMap<>();

    private boolean reachable = true;
    private boolean stopFails;

    /** service -> what Arcane says about its image. Absent means UNKNOWN, which is never work. */
    private final Map<String, ImageResult.State> images = new LinkedHashMap<>();

    /** Services whose recreate is refused - a 404 on the project id, a pull that failed. */
    private final java.util.Set<String> recreateRefused = new java.util.LinkedHashSet<>();

    /** volume -> how many polls it stays running before it settles. */
    private final Map<String, Integer> backupDelay = new LinkedHashMap<>();

    /** volume -> what it settles as. Absent means "succeeds". */
    private final Map<String, Boolean> backupSucceeds = new LinkedHashMap<>();

    /** Volumes whose POST is refused outright - Arcane unreachable, a 404, a 409. */
    private final java.util.Set<String> backupRefused = new java.util.LinkedHashSet<>();

    private final Map<String, Integer> polls = new LinkedHashMap<>();

    FakeArcane running(final String... names) {
        for (final String name : names) {
            services.put(name, new ServiceRuntime(name, name + "-container", "running", "healthy"));
        }
        return this;
    }

    /** Arcane reports a newer image for these services. */
    FakeArcane imageOutdated(final String... names) {
        for (final String name : names) {
            images.put(name, ImageResult.State.OUTDATED);
        }
        return this;
    }

    /** Arcane has checked these and they are current - which is not the same as never checked. */
    FakeArcane imageCurrent(final String... names) {
        for (final String name : names) {
            images.put(name, ImageResult.State.UP_TO_DATE);
        }
        return this;
    }

    /** The recreate of these services is refused, so they keep running their old image. */
    FakeArcane recreateRefused(final String... names) {
        recreateRefused.addAll(List.of(names));
        return this;
    }

    @Override
    public @NotNull ImageResult images() {
        if (!reachable) {
            return ImageResult.unreachable("no Arcane");
        }
        return ImageResult.of(images);
    }

    @Override
    public @NotNull RedeployResult recreate(final @NotNull String service) {
        calls.add("recreate:" + service);
        if (!reachable) {
            return RedeployResult.refused("no Arcane");
        }
        if (recreateRefused.contains(service)) {
            return RedeployResult.refused("refused");
        }
        // A recreate is a new container, and the run has to keep working against the service name
        // rather than the id it remembered. Handing back a different id is what makes a test that
        // relies on the old one fail here rather than on the deployment.
        services.put(service, new ServiceRuntime(service, service + "-container-2", "running", "healthy"));
        return RedeployResult.triggered("HTTP 200");
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

    /** This volume's snapshot never starts. */
    FakeArcane backupRefused(final String volume) {
        backupRefused.add(volume);
        return this;
    }

    /** This volume's snapshot starts and then reports failed. */
    FakeArcane backupFails(final String volume) {
        backupSucceeds.put(volume, false);
        return this;
    }

    /** This volume's snapshot stays RUNNING for ever, so only the patience can end the wait. */
    FakeArcane backupNeverFinishes(final String volume) {
        backupDelay.put(volume, Integer.MAX_VALUE);
        return this;
    }

    @Override
    public @NotNull BackupResult backup(final @NotNull String volume) {
        calls.add("backup:" + volume);
        if (!reachable || backupRefused.contains(volume)) {
            return BackupResult.refused("refused");
        }
        return BackupResult.running(volume + "-backup", "started");
    }

    @Override
    public @NotNull BackupResult backupState(final @NotNull String volume,
                                             final @NotNull String backupId) {
        // Recorded, so an ordering assertion can see a poll and not only a POST. Without this the
        // "both POSTs go out before the first poll" test would pass on an implementation that
        // polls after each POST, which is the shape it exists to forbid: waiting per volume holds
        // the network down for the sum of the uploads rather than the longest.
        calls.add("poll:" + volume);
        final int seen = polls.merge(volume, 1, Integer::sum);
        if (seen <= backupDelay.getOrDefault(volume, 0)) {
            return BackupResult.running(backupId, "running");
        }
        return backupSucceeds.getOrDefault(volume, true)
                ? BackupResult.succeeded(backupId, "saved")
                : BackupResult.failed(backupId, "the archive could not be written");
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
