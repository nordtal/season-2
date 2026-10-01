package eu.nordtal.s2.steward.docker;

import eu.nordtal.s2.steward.ops.ContainerOps;
import eu.nordtal.s2.steward.ops.ImageResult;
import eu.nordtal.s2.steward.ops.RedeployResult;
import eu.nordtal.s2.steward.ops.RuntimeResult;
import eu.nordtal.s2.steward.ops.ServiceRuntime;
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
 * {@link ContainerOps} against the Docker daemon itself, which can ask a registry for the current digest.
 *
 * Creating a container needs the compose file, so {@link #deploy} and {@link #recreate} refuse.
 */
public final class DockerOps implements ContainerOps {

    private static final Logger log = LoggerFactory.getLogger(DockerOps.class);

    /** How long a container gets to stop before Docker kills it. */
    private static final int STOP_GRACE_SECONDS = 30;

    private final Docker docker;
    private final String project;

    public DockerOps(final Docker docker, final String project) {
        this.docker = docker;
        this.project = project;
    }

    @Override
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
     * Stops a container, and refuses if Docker had to kill it (exit code 137), since a half-saved world is no backup.
     *
     * An unreadable inspect gives {@link RedeployResult#unverified}, which {@code Runner.settle} weighs.
     */
    @Override
    public RedeployResult stop(final String containerId) {
        try {
            docker.stop(containerId, STOP_GRACE_SECONDS);
        } catch (DockerException e) {
            return RedeployResult.refused("stopping " + shortId(containerId) + ": " + e.getMessage());
        }
        try {
            if (docker.inspect(containerId).wasKilled()) {
                return RedeployResult.refused(shortId(containerId) + " did not shut down within "
                        + STOP_GRACE_SECONDS + " seconds and was killed (exit 137). Whatever it was"
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

    @Override
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
    @Override
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

    /** Refused: recreating from an inspect would rebuild a container definition that {@code steward-deployer} owns. */
    @Override
    public RedeployResult deploy(final String service) {
        return refusal("deploying", service);
    }

    @Override
    public RedeployResult recreate(final String service) {
        return refusal("recreating", service);
    }

    /** The same refusal for both: creating a container needs the compose file, which {@code DeployerRecreate} has. */
    private static RedeployResult refusal(final String verb, final String service) {
        return RedeployResult.refused(verb + " " + service + " is steward-deployer's: it has the "
                + "compose file, and a container rebuilt from an inspect would drift from it "
                + "silently. Not wired from here yet - see §8b.");
    }

    private static String shortId(final String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    /** {@code getMessage()} is null for some exceptions, so the class name stands in. */
    private static String messageOf(final Exception failure) {
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
