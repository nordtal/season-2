package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.ops.ServiceRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link ContainerOps} that answers from a map instead of from a Docker daemon.
 *
 * <p>Everything the update sequence does is an ordering decision, and the only way to see one is to
 * record the calls. {@link #calls} is that record, in order: {@code stop:smp}, {@code backup:mc-smp},
 * {@code start:smp}. A backup run's whole correctness is that middle entry sitting between the
 * other two - a snapshot taken of a server that is still running fails at RESTORE and nowhere
 * else.</p>
 */
final class FakeContainers implements ContainerOps {

    /** Every stop and start, in the order they were asked for. */
    final List<String> calls = new ArrayList<>();

    /** service -> its current runtime. Mutated by the test to make a server come back, or not. */
    private final Map<String, ServiceRuntime> services = new LinkedHashMap<>();

    private boolean reachable = true;
    private boolean stopFails;

    /** Services whose stop succeeds and whose ending cannot be read back afterwards. */
    private final java.util.Set<String> stopUnverified = new java.util.LinkedHashSet<>();

    /** service -> what the drift check found. Absent means UNKNOWN, which is never work. */
    private final Map<String, ImageResult.State> images = new LinkedHashMap<>();

    /** Services whose deploy or recreate is refused - a 404 on the project id, a pull that failed. */
    private final java.util.Set<String> recreateRefused = new java.util.LinkedHashSet<>();

    /** volume -> how many polls it stays running before it settles. */
    private final Map<String, Integer> backupDelay = new LinkedHashMap<>();

    /** volume -> what it settles as. Absent means "succeeds". */
    private final Map<String, Boolean> backupSucceeds = new LinkedHashMap<>();

    /** Volumes whose snapshot is refused outright before anything is written. */
    private final java.util.Set<String> backupRefused = new java.util.LinkedHashSet<>();

    private final Map<String, Integer> polls = new LinkedHashMap<>();

    FakeContainers running(final String... names) {
        for (final String name : names) {
            services.put(name, new ServiceRuntime(name, name + "-container", "running", "healthy"));
        }
        return this;
    }

    /** The registry has a newer image for these services. */
    FakeContainers imageOutdated(final String... names) {
        for (final String name : names) {
            images.put(name, ImageResult.State.OUTDATED);
        }
        return this;
    }

    /** These were checked and are current - which is not the same as never checked. */
    FakeContainers imageCurrent(final String... names) {
        for (final String name : names) {
            images.put(name, ImageResult.State.UP_TO_DATE);
        }
        return this;
    }

    /** The deploy of these services is refused, so they keep running their old image. */
    FakeContainers recreateRefused(final String... names) {
        recreateRefused.addAll(List.of(names));
        return this;
    }

    /** Services whose container comes up and never passes its healthcheck. */
    private final java.util.Set<String> neverHealthy = new java.util.LinkedHashSet<>();

    /**
     * These come back {@code running} and {@code unhealthy}, for ever.
     *
     * <p>The interesting failure of a standby: the container exists, compose is happy, and the
     * plugin inside it threw in {@code onEnable}. A run that reads "it started" rather than "it is
     * back" would park every player on it.</p>
     */
    FakeContainers neverHealthy(final String... names) {
        neverHealthy.addAll(List.of(names));
        return this;
    }

    @Override
    public ImageResult images() {
        if (!reachable) {
            return ImageResult.unreachable("no docker socket");
        }
        return ImageResult.of(images);
    }

    @Override
    public RedeployResult deploy(final String service) {
        // "recreate:" and not "deploy:", so that every existing assertion about the order of a run
        // keeps meaning what it meant. What the two routes differ in is whether an image is
        // fetched, and this fake has no images to fetch.
        return made("recreate:" + service, service);
    }

    @Override
    public RedeployResult recreate(final String service) {
        return made("recreate-local:" + service, service);
    }

    private RedeployResult made(final String call, final String service) {
        calls.add(call);
        if (!reachable) {
            return RedeployResult.refused("no docker socket");
        }
        if (recreateRefused.contains(service)) {
            return RedeployResult.refused("refused");
        }
        // A recreate is a new container, and the run has to keep working against the service name
        // rather than the id it remembered. Handing back a different id is what makes a test that
        // relies on the old one fail here rather than on the deployment.
        services.put(
                service,
                new ServiceRuntime(
                        service,
                        service + "-container-2",
                        "running",
                        neverHealthy.contains(service) ? "unhealthy" : "healthy"));
        return RedeployResult.triggered("HTTP 200");
    }

    /** The daemon is not answering at all - the case the whole run must refuse to start on. */
    FakeContainers unreachable() {
        reachable = false;
        return this;
    }

    /** Every stop is refused, so nothing may be installed and nothing may be started. */
    FakeContainers stopFails() {
        stopFails = true;
        return this;
    }

    /**
     * These stops are accepted and nothing can say how they ended - the run 23 shape.
     *
     * <p>Docker's stop call succeeds whether the server shut down or was killed at the end of the
     * grace period, so the real client inspects the container afterwards; this is the case where
     * that inspect itself fails. The container is stopped, and whether the world had finished
     * writing is a question nobody can answer any more.</p>
     */
    FakeContainers stopUnverified(final String... names) {
        stopUnverified.addAll(List.of(names));
        return this;
    }

    /** A service that is up but whose plugin died - running, unhealthy. The interesting failure. */
    void sick(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running", "unhealthy"));
    }

    void back(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running", "healthy"));
    }

    /** This volume's snapshot never starts. */
    FakeContainers backupRefused(final String volume) {
        backupRefused.add(volume);
        return this;
    }

    /** This volume's snapshot starts and then reports failed. */
    FakeContainers backupFails(final String volume) {
        backupSucceeds.put(volume, false);
        return this;
    }

    @Override
    public RuntimeResult runtime() {
        return reachable
                ? RuntimeResult.of(List.copyOf(services.values()))
                : RuntimeResult.unreachable("the docker daemon is not answering");
    }

    @Override
    public RedeployResult stop(final String containerId) {
        calls.add("stop:" + containerId);
        if (stopFails) {
            return RedeployResult.refused("refused");
        }
        services.computeIfPresent(
                service(containerId), (name, entry) -> new ServiceRuntime(name, entry.containerId(), "exited", null));
        if (stopUnverified.contains(service(containerId))) {
            return RedeployResult.unverified(containerId + " was stopped, and how it ended could"
                    + " not be read back: reading the docker socket");
        }
        return RedeployResult.triggered("HTTP 204");
    }

    @Override
    public RedeployResult start(final String containerId) {
        calls.add("start:" + containerId);
        // Started, and NOT healthy: that is the whole distinction the verify step exists for.
        services.computeIfPresent(
                service(containerId),
                (name, entry) -> new ServiceRuntime(name, entry.containerId(), "running", "starting"));
        return RedeployResult.triggered("HTTP 204");
    }

    private static String service(final String containerId) {
        return containerId.endsWith("-container")
                ? containerId.substring(0, containerId.length() - "-container".length())
                : containerId;
    }
}
