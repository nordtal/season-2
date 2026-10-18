package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.ServiceRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link ContainerOps} that answers from a map and records every call in {@link #calls}. */
final class FakeContainers implements ContainerOps {

    /** Every stop and start, in order. */
    final List<String> calls = new ArrayList<>();

    /** service -> the recent lines of its log, oldest first. */
    private final Map<String, List<String>> logs = new LinkedHashMap<>();

    /** service -> its current runtime. Mutated by the test to make a server come back, or not. */
    private final Map<String, ServiceRuntime> services = new LinkedHashMap<>();

    private boolean reachable = true;
    private boolean stopFails;

    /** Services whose stop succeeds and whose end cannot be read back. */
    private final java.util.Set<String> stopUnverified = new java.util.LinkedHashSet<>();

    /** The result of the drift check per service. Absent means UNKNOWN. */
    private final Map<String, ImageResult.State> images = new LinkedHashMap<>();

    /** Services whose deploy or recreate is refused. */
    private final java.util.Set<String> recreateRefused = new java.util.LinkedHashSet<>();

    /** Services whose image this host does not have. */
    private final java.util.Set<String> imageNotHere = new java.util.LinkedHashSet<>();

    /** How each volume settles. Absent means success. */
    private final Map<String, Boolean> backupSucceeds = new LinkedHashMap<>();

    /** Volumes whose snapshot is refused before anything is written. */
    private final java.util.Set<String> backupRefused = new java.util.LinkedHashSet<>();

    private final AgentWire.Topology topology = new AgentWire.Topology(
            eu.nordtal.season.stewardagent.topology.DeclaredTopology.topology().services(),
            List.of(
                    "nordtal-s2_mc-smp",
                    "nordtal-s2_mc-smp-plugins",
                    "nordtal-s2_mc-hunger-games",
                    "nordtal-s2_mc-hunger-games-plugins"),
            java.util.Map.of(
                    "nordtal-s2_mc-smp", List.of("smp"),
                    "nordtal-s2_mc-smp-plugins", List.of("smp"),
                    "nordtal-s2_mc-hunger-games", List.of("hunger-games"),
                    "nordtal-s2_mc-hunger-games-plugins", List.of("hunger-games")));

    @Override
    public AgentWire.Topology topology() {
        return topology;
    }

    FakeContainers running(final String... names) {
        for (final String name : names) {
            services.put(name, new ServiceRuntime(name, name + "-container", "running", "healthy", 0));
        }
        return this;
    }

    FakeContainers imageOutdated(final String... names) {
        for (final String name : names) {
            images.put(name, ImageResult.State.OUTDATED);
        }
        return this;
    }

    FakeContainers imageCurrent(final String... names) {
        for (final String name : names) {
            images.put(name, ImageResult.State.UP_TO_DATE);
        }
        return this;
    }

    FakeContainers recreateRefused(final String... names) {
        recreateRefused.addAll(List.of(names));
        return this;
    }

    FakeContainers imageNotHere(final String... names) {
        imageNotHere.addAll(List.of(names));
        return this;
    }

    private final java.util.Set<String> neverHealthy = new java.util.LinkedHashSet<>();

    /** These come back {@code running} and {@code unhealthy} for ever. */
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
        // "recreate:" and not "deploy:", so the existing order assertions keep their meaning.
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

    @Override
    public Pruned pruneImages() {
        calls.add("prune-images");
        return new Pruned(3, Map.of("sha256:old", 2_000_000_000L), 2_000_000_000L, null);
    }

    private RedeployResult made(final String call, final String service) {
        calls.add(call);
        if (!reachable) {
            return RedeployResult.refused("no docker socket");
        }
        if (recreateRefused.contains(service)) {
            return RedeployResult.refused("refused");
        }
        // A recreate makes a new container, so the id changes.
        services.put(
                service,
                new ServiceRuntime(
                        service,
                        service + "-container-2",
                        "running",
                        neverHealthy.contains(service) ? "unhealthy" : "healthy",
                        0));
        return RedeployResult.triggered("HTTP 200");
    }

    /** The daemon does not answer. */
    FakeContainers unreachable() {
        reachable = false;
        return this;
    }

    /** Every stop is refused. */
    FakeContainers stopFails() {
        stopFails = true;
        return this;
    }

    /** These stops are accepted and the inspect afterwards fails. */
    FakeContainers stopUnverified(final String... names) {
        stopUnverified.addAll(List.of(names));
        return this;
    }

    void sick(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running", "unhealthy", 0));
    }

    void settle() {
        services.replaceAll((name, entry) -> "starting".equals(entry.health())
                ? new ServiceRuntime(name, entry.containerId(), "running", "healthy", entry.restarts())
                : entry);
    }

    void back(final String service) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running", "healthy", 0));
    }

    FakeContainers backupRefused(final String volume) {
        backupRefused.add(volume);
        return this;
    }

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
                service(containerId),
                (name, entry) -> new ServiceRuntime(name, entry.containerId(), "exited", null, entry.restarts()));
        if (stopUnverified.contains(service(containerId))) {
            return RedeployResult.unverified(containerId + " was stopped, and how it ended could"
                    + " not be read back: reading the docker socket");
        }
        return RedeployResult.triggered("HTTP 204");
    }

    @Override
    public List<String> lastLines(final String containerId, final int count) {
        final List<String> lines = logs.getOrDefault(service(containerId), List.of());
        return lines.subList(Math.max(0, lines.size() - count), lines.size());
    }

    /** The plugin refuses the start and the entrypoint holds the container. */
    void refuses(final String service, final String reason) {
        services.put(service, new ServiceRuntime(service, service + "-container", "running", "starting", 0));
        logs.put(service, List.of("2026-10-18T17:10:00Z [nordtal] REFUSED: " + reason));
    }

    /** Docker's restart policy starts the container again after its server exited. */
    void restarted(final String service, final String exitLine) {
        final ServiceRuntime was = java.util.Objects.requireNonNull(services.get(service));
        services.put(
                service, new ServiceRuntime(service, was.containerId(), "running", "starting", was.restarts() + 1));
        logs.put(service, List.of(exitLine, "[nordtal] starting paper 26.2 build 1"));
    }

    @Override
    public void handOverMounts(final String service) {
        calls.add("hand-over-mounts:" + service);
    }

    @Override
    public RedeployResult start(final String containerId) {
        calls.add("start:" + containerId);
        // Started and not healthy.
        services.computeIfPresent(
                service(containerId),
                (name, entry) ->
                        new ServiceRuntime(name, entry.containerId(), "running", "starting", entry.restarts()));
        return RedeployResult.triggered("HTTP 204");
    }

    private static String service(final String containerId) {
        return containerId.endsWith("-container")
                ? containerId.substring(0, containerId.length() - "-container".length())
                : containerId;
    }
}
