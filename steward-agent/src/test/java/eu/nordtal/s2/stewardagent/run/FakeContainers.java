package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.ServiceRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link ContainerOps} that answers from a map and records every call in order in {@link #calls}. */
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

    /** Services whose deploy or recreate is refused, like a 404 on the project id or a failed pull. */
    private final java.util.Set<String> recreateRefused = new java.util.LinkedHashSet<>();

    /** Services whose image this host does not have, as a one-shot meets the next release's. */
    private final java.util.Set<String> imageNotHere = new java.util.LinkedHashSet<>();

    /** volume -> what it settles as. Absent means "succeeds". */
    private final Map<String, Boolean> backupSucceeds = new LinkedHashMap<>();

    /** Volumes whose snapshot is refused outright before anything is written. */
    private final java.util.Set<String> backupRefused = new java.util.LinkedHashSet<>();

    /** compose.yml's services as they ship, with the backup set its mounts give. */
    private final AgentWire.Topology topology = new AgentWire.Topology(
            eu.nordtal.s2.stewardagent.topology.ComposeFile.topology().services(),
            List.of("nordtal-s2_mc-smp", "nordtal-s2_mc-smp-plugins", "nordtal-s2_mc-hunger-games-plugins"),
            java.util.Map.of(
                    "nordtal-s2_mc-smp", List.of("smp"),
                    "nordtal-s2_mc-smp-plugins", List.of("smp"),
                    "nordtal-s2_mc-hunger-games-plugins", List.of("hunger-games")));

    @Override
    public AgentWire.Topology topology() {
        return topology;
    }

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

    /** These were checked and are current, which is not the same as never checked. */
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

    /** This host has no image for these yet, so a recreate is refused and only a fetch makes one. */
    FakeContainers imageNotHere(final String... names) {
        imageNotHere.addAll(List.of(names));
        return this;
    }

    /** Services whose container comes up and never passes its healthcheck. */
    private final java.util.Set<String> neverHealthy = new java.util.LinkedHashSet<>();

    /** These come back {@code running} and {@code unhealthy} for ever, like a standby whose plugin failed to enable. */
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
        // "recreate:" not "deploy:", so every existing order assertion keeps meaning what it meant.
        return made("recreate:" + service, service);
    }

    @Override
    public RedeployResult recreate(final String service) {
        if (imageNotHere.contains(service)) {
            calls.add("recreate-local:" + service);
            return RedeployResult.refused("no image for " + service + " on this host");
        }
        return made("recreate-local:" + service, service);
    }

    @Override
    public RedeployResult standby(final String service) {
        if (imageNotHere.remove(service)) {
            calls.add("fetch:" + service);
        }
        return made("recreate-local:" + service, service);
    }

    @Override
    public RedeployResult migrate() {
        calls.add("migrate");
        return reachable ? RedeployResult.triggered("exit 0") : RedeployResult.refused("no docker socket");
    }

    @Override
    public String oneShot() {
        return "nordtal-s2-steward-agent-run";
    }

    @Override
    public RedeployResult handOver(final long id, final String release) {
        calls.add("hand-over:" + id + "@" + release);
        return reachable ? RedeployResult.triggered("running") : RedeployResult.refused("no docker socket");
    }

    @Override
    public RedeployResult renewAgent() {
        return made("renew:steward-agent", "steward-agent");
    }

    private RedeployResult made(final String call, final String service) {
        calls.add(call);
        if (!reachable) {
            return RedeployResult.refused("no docker socket");
        }
        if (recreateRefused.contains(service)) {
            return RedeployResult.refused("refused");
        }
        // A recreate is a new container; a different id here fails a test that relies on the old one.
        services.put(
                service,
                new ServiceRuntime(
                        service,
                        service + "-container-2",
                        "running",
                        neverHealthy.contains(service) ? "unhealthy" : "healthy"));
        return RedeployResult.triggered("HTTP 200");
    }

    /** The daemon is not answering at all, which the whole run must refuse to start on. */
    FakeContainers unreachable() {
        reachable = false;
        return this;
    }

    /** Every stop is refused, so nothing may be installed and nothing may be started. */
    FakeContainers stopFails() {
        stopFails = true;
        return this;
    }

    /** These stops are accepted, but the inspect afterwards fails, so nothing can say how they ended. */
    FakeContainers stopUnverified(final String... names) {
        stopUnverified.addAll(List.of(names));
        return this;
    }

    /** A service that is up but whose plugin died: running, unhealthy. */
    void sick(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running", "unhealthy"));
    }

    /** Every container that was started passes its healthcheck, as all of them do a while later. */
    void settle() {
        services.replaceAll((name, entry) -> "starting".equals(entry.health())
                ? new ServiceRuntime(name, entry.containerId(), "running", "healthy")
                : entry);
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
