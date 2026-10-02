package eu.nordtal.s2.stewardagent.docker;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.ServiceRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The project's containers as the daemon has them: their state, stopping and starting one, and image drift.
 *
 * Creating a container needs the compose file, so that is {@code Compose}'s, never this class's.
 */
public final class Containers {

    private static final Logger log = LoggerFactory.getLogger(Containers.class);

    /** The longest grace the client's wait leaves room for; a longer {@code stop_grace_period} is cut to it. */
    static final int LONGEST_GRACE_SECONDS =
            (int) AgentWire.LONGEST_STOP.minus(Docker.KILL_MARGIN).toSeconds();

    private final Docker docker;
    private final String project;

    public Containers(final Docker docker, final String project) {
        this.docker = docker;
        this.project = project;
    }

    /**
     * Every container of the project, inspected, each with its last sample; an unreadable daemon is said, not thrown.
     *
     * @param samples the sampler's last reading per service
     */
    public AgentWire.Containers list(final Map<String, AgentWire.Reading> samples) {
        final List<AgentWire.Container> all = new ArrayList<>();
        try {
            for (final Docker.Container container : docker.containers(project)) {
                if (container.service() != null) {
                    all.add(describe(container, samples.get(container.service()), null));
                }
            }
        } catch (DockerException e) {
            log.warn("could not list the containers", e);
            return new AgentWire.Containers(false, messageOf(e), List.of());
        }
        all.sort(java.util.Comparator.comparing(AgentWire.Container::service));
        return new AgentWire.Containers(true, null, List.copyOf(all));
    }

    /** One service's container with the registry digests of its image, preferring the running one. */
    public Optional<AgentWire.Container> one(final String service, final AgentWire.@Nullable Reading sample) {
        return docker.containers(project).stream()
                .filter(container -> service.equals(container.service()))
                .sorted(java.util.Comparator.comparing(container -> !container.isRunning()))
                .findFirst()
                .map(container -> describe(container, sample, docker.repoDigests(container.imageId())));
    }

    /** One row; a container that will not be inspected keeps what the list said rather than failing the table. */
    private AgentWire.Container describe(
            final Docker.Container container,
            final AgentWire.@Nullable Reading sample,
            final @Nullable List<String> digests) {
        String state = String.valueOf(container.state());
        String health = null;
        String startedAt = null;
        try {
            final Docker.Inspection inspection = docker.inspect(container.id());
            state = String.valueOf(inspection.state());
            health = inspection.health();
            startedAt = inspection.startedAt();
        } catch (DockerException e) {
            log.debug("could not inspect {}", container.id(), e);
        }
        return new AgentWire.Container(
                java.util.Objects.requireNonNull(container.service(), "only compose services are described"),
                container.id(),
                container.image(),
                container.imageId(),
                state,
                container.status(),
                health,
                startedAt,
                "running".equalsIgnoreCase(state) ? sample : null,
                digests);
    }

    public RuntimeResult runtime() {
        try {
            final List<ServiceRuntime> services = new ArrayList<>();
            for (final Docker.Container container : docker.containers(project)) {
                final String service = container.service();
                if (service == null) {
                    continue;
                }
                // Inspect rather than parsing the list's Status string ("Up 19 hours (healthy)").
                final Docker.Inspection inspection = docker.inspect(container.id());
                services.add(new ServiceRuntime(service, container.id(), inspection.state(), inspection.health()));
            }
            return RuntimeResult.of(services);
        } catch (DockerException e) {
            log.warn("could not read the container runtime", e);
            return RuntimeResult.unreachable(messageOf(e));
        }
    }

    /**
     * Stops a container within its {@code stop_grace_period}, refusing if Docker had to kill it (exit code 137).
     *
     * The grace is compose's, its one source. An unreadable ending is {@link RedeployResult#unverified}.
     */
    public RedeployResult stop(final String containerId) {
        final int grace;
        try {
            grace = Math.min(docker.inspect(containerId).stopTimeout(), LONGEST_GRACE_SECONDS);
            docker.stop(containerId, grace);
        } catch (DockerException e) {
            return RedeployResult.refused("stopping " + shortId(containerId) + ": " + e.getMessage());
        }
        try {
            if (docker.inspect(containerId).wasKilled()) {
                return RedeployResult.refused(shortId(containerId) + " did not shut down within "
                        + grace + " seconds and was killed (exit 137). Whatever it was"
                        + " writing was cut off, so nothing saved from its volumes now counts as a"
                        + " backup");
            }
        } catch (DockerException e) {
            // Not knowing how the stop ended is not knowing it ended badly; refusing would take the network down.
            log.warn("could not read how {} exited", shortId(containerId), e);
            return RedeployResult.unverified(shortId(containerId) + " was stopped, and how it ended"
                    + " could not be read back: " + e.getMessage());
        }
        return RedeployResult.triggered("stop asked for " + shortId(containerId));
    }

    public RedeployResult start(final String containerId) {
        try {
            docker.start(containerId);
            return RedeployResult.triggered("start asked for " + shortId(containerId));
        } catch (DockerException e) {
            return RedeployResult.refused("starting " + shortId(containerId) + ": " + e.getMessage());
        }
    }

    /**
     * Which services run an image the registry has moved past, by digest.
     *
     * A local build is {@code LOCAL}; a question nobody could answer is {@code UNKNOWN}, never current.
     */
    public ImageResult images() {
        try {
            final Map<String, ImageResult.State> states = new HashMap<>();
            final Set<String> unverifiable = new LinkedHashSet<>();
            // One answer per image, not per container: several services can run the same image.
            final Map<String, ImageCheck> asked = new HashMap<>();
            for (final Docker.Container container : docker.containers(project)) {
                if (container.service() == null || !container.isRunning()) {
                    continue;
                }
                String reference = container.image();
                if (reference == null || reference.isBlank()) {
                    states.put(container.service(), ImageResult.State.UNKNOWN);
                    unverifiable.add(container.service());
                    continue;
                }
                if (isOrphanedShortId(reference, container.imageId())) {
                    // A retagged local rebuild prints a bare short id; ask Config for the compose name.
                    final String friendly = docker.inspect(container.id()).image();
                    if (friendly != null && !friendly.isBlank()) {
                        reference = friendly;
                    }
                }
                final String resolvedReference = reference;
                final ImageCheck check = asked.computeIfAbsent(
                        resolvedReference + "@" + container.imageId(),
                        ignored -> check(resolvedReference, container.imageId()));
                states.put(container.service(), check.state());
                if (check.state() == ImageResult.State.UNKNOWN) {
                    unverifiable.add(container.service());
                }
            }
            return ImageResult.of(states, unverifiable);
        } catch (DockerException e) {
            log.warn("could not compare images against their registries", e);
            return ImageResult.unreachable(messageOf(e));
        }
    }

    /** Whether {@code reference} is the twelve-character short id {@code docker ps} prints for an unnamed image. */
    private static boolean isOrphanedShortId(final String reference, final @Nullable String imageId) {
        return imageId != null
                && imageId.startsWith("sha256:")
                && reference.length() == 12
                && !reference.contains("/")
                && !reference.contains(":")
                && imageId.regionMatches(true, "sha256:".length(), reference, 0, reference.length());
    }

    /**
     * One image, checked against its registry.
     *
     * @param reference what the container was created from, for example {@code ghcr.io/nordtal/smp:latest}
     * @param imageId the image the container actually runs, as a sha256
     */
    public ImageCheck check(final String reference, final @Nullable String imageId) {
        final Optional<Docker.ImageIdentity> identity = docker.imageIdentity(imageId);
        if (identity.isEmpty()) {
            // Gone from the daemon's store; what its tag now means is still worth asking, though not proof.
            final Optional<Docker.ImageIdentity> current = docker.imageIdentity(reference);
            if (current.isPresent() && current.get().builtLocally()) {
                return new ImageCheck(
                        ImageResult.State.LOCAL,
                        reference + " no longer matches what this container runs: its tag was"
                                + " rebuilt locally while the container was only restarted, not"
                                + " recreated. The exact image this container runs cannot be read"
                                + " any more, and a --force-recreate (or a real update run) replaces"
                                + " it with that local build");
            }
            return new ImageCheck(
                    ImageResult.State.UNKNOWN,
                    reference + "'s image no longer exists in this daemon's store, and what its tag"
                            + " currently means could not be read either");
        }
        if (identity.get().builtLocally()) {
            return new ImageCheck(
                    ImageResult.State.LOCAL,
                    reference + " was built on this host and never published - the next real update"
                            + " run replaces it silently");
        }
        final List<String> local = identity.get().repoDigests();
        if (local.isEmpty()) {
            // No Identity.Build and no digest: built here and never pushed, under the graphdriver.
            return new ImageCheck(
                    ImageResult.State.LOCAL,
                    reference + " carries no registry digest and was not pulled either - built on"
                            + " this host and never published, the next real update run replaces it"
                            + " silently");
        }
        final Optional<String> remote = docker.registryDigest(reference);
        if (remote.isEmpty()) {
            return new ImageCheck(ImageResult.State.UNKNOWN, "the registry did not answer for " + reference);
        }
        final String wanted = remote.get();
        final boolean carriesIt = local.stream().anyMatch(digest -> digest.endsWith("@" + wanted));
        return new ImageCheck(carriesIt ? ImageResult.State.UP_TO_DATE : ImageResult.State.OUTDATED, null);
    }

    /** The state, and why it could not be established when that is the answer. */
    public record ImageCheck(
            ImageResult.State state, @Nullable String reason) {}

    private static String shortId(final String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    /** {@code getMessage()} is null for some exceptions, so the class name stands in. */
    private static String messageOf(final Exception failure) {
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
