package eu.nordtal.s2.steward.worker.docker;

import eu.nordtal.s2.steward.worker.ops.BackupResult;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.ops.ServiceRuntime;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ContainerOps} against the Docker daemon itself, replacing the half of Arcane that only
 * ever read.
 *
 * <h2>Why this exists rather than another Arcane call</h2>
 * Arcane's image check never asked a registry. It compared what it had, answered "up to date", and
 * four releases ran behind while nothing said so - {@code todo.md} A24, found on 2026-09-12. The
 * daemon can answer the question properly ({@code GET /distribution/{ref}/json}), and the daemon is
 * on the other end of a socket this container already needs for logs and the console. Two of the
 * three reasons to keep Arcane disappear with this class.
 *
 * <h2>What it still cannot do</h2>
 * <b>Creating containers.</b> {@link #recreate} needs the compose file, which lives in
 * {@code steward-deployer} (§8b), and this class refuses rather than improvising a container
 * definition out of an inspect. <b>Volume snapshots</b> are steward-worker's own job from §9a and
 * are not wired here yet; until they are, the backup calls say so instead of answering something
 * that looks like a snapshot nobody took.
 */
public final class DockerOps implements ContainerOps {

    private static final Logger log = LoggerFactory.getLogger(DockerOps.class);

    /** How long a container gets to stop before Docker kills it. */
    private static final int STOP_GRACE_SECONDS = 30;

    private final Docker docker;
    private final String project;

    public DockerOps(final @NotNull Docker docker, final @NotNull String project) {
        this.docker = docker;
        this.project = project;
    }

    @Override
    public @NotNull RuntimeResult runtime() {
        try {
            final List<ServiceRuntime> services = new ArrayList<>();
            for (final Docker.Container container : docker.containers(project)) {
                if (container.service() == null) {
                    continue;
                }
                // Inspect rather than reading the list's Status string: "Up 19 hours (healthy)" is
                // a sentence for a person, and parsing it is how a health check becomes a guess.
                final Docker.Inspection inspection = docker.inspect(container.id());
                services.add(new ServiceRuntime(container.service(), container.id(),
                        inspection.state(), inspection.health()));
            }
            return RuntimeResult.of(services);
        } catch (DockerException e) {
            log.warn("could not read the container runtime", e);
            return RuntimeResult.unreachable(e.getMessage());
        }
    }

    @Override
    public @NotNull RedeployResult stop(final @NotNull String containerId) {
        try {
            docker.stop(containerId, STOP_GRACE_SECONDS);
            return RedeployResult.triggered("stop asked for " + shortId(containerId));
        } catch (DockerException e) {
            return RedeployResult.refused("stopping " + shortId(containerId) + ": " + e.getMessage());
        }
    }

    @Override
    public @NotNull RedeployResult start(final @NotNull String containerId) {
        try {
            docker.start(containerId);
            return RedeployResult.triggered("start asked for " + shortId(containerId));
        } catch (DockerException e) {
            return RedeployResult.refused("starting " + shortId(containerId) + ": " + e.getMessage());
        }
    }

    /**
     * Which services run an image the registry has moved past.
     *
     * <p>Per service: the digest the running container's image actually carries, against the digest
     * the registry answers for the reference it was created from. Three outcomes, and the third one
     * is the one that matters:</p>
     *
     * <ul>
     *   <li>the registry's digest is among the image's - {@code UP_TO_DATE}</li>
     *   <li>it is not - {@code OUTDATED}, and there is work</li>
     *   <li>the question could not be asked or answered - {@code UNKNOWN} <b>and</b> a line in
     *       {@code unverifiable}. A locally built image has no digest at all, a private registry
     *       answers nothing without credentials, and a machine with no network answers nothing at
     *       all. None of those is "current", and reporting them as current is exactly the failure
     *       this check exists to end.</li>
     * </ul>
     */
    @Override
    public @NotNull ImageResult images() {
        try {
            final Map<String, ImageResult.State> states = new HashMap<>();
            final Set<String> unverifiable = new LinkedHashSet<>();
            for (final Docker.Container container : docker.containers(project)) {
                if (container.service() == null || !container.isRunning()) {
                    continue;
                }
                final String reference = container.image();
                if (reference == null || reference.isBlank()) {
                    states.put(container.service(), ImageResult.State.UNKNOWN);
                    unverifiable.add(container.service());
                    continue;
                }
                final ImageCheck check = check(reference, container.imageId());
                states.put(container.service(), check.state());
                if (check.reason() != null) {
                    unverifiable.add(container.service());
                }
            }
            return ImageResult.of(states, unverifiable);
        } catch (DockerException e) {
            log.warn("could not compare images against their registries", e);
            return ImageResult.unreachable(e.getMessage());
        }
    }

    /**
     * One image, checked against its registry. Public because it is the whole of the drift answer
     * and it is worth being able to ask it about a single reference - which is also how it is
     * tested: a tag is bent by hand on this host and the answer has to change, then change back.
     *
     * @param reference what the container was created from, e.g. {@code ghcr.io/nordtal/smp:latest}
     * @param imageId   the image the container actually runs, as a sha256
     */
    public @NotNull ImageCheck check(final @NotNull String reference, final String imageId) {
        final List<String> local = docker.repoDigests(imageId);
        if (local.isEmpty()) {
            // Built here and never pushed, which during the alpha is true of steward-ui by design.
            return new ImageCheck(ImageResult.State.UNKNOWN,
                    reference + " carries no registry digest - it was built locally and pushed nowhere");
        }
        final Optional<String> remote = docker.registryDigest(reference);
        if (remote.isEmpty()) {
            return new ImageCheck(ImageResult.State.UNKNOWN,
                    "the registry did not answer for " + reference);
        }
        final String wanted = remote.get();
        final boolean carriesIt = local.stream().anyMatch(digest -> digest.endsWith("@" + wanted));
        return new ImageCheck(carriesIt ? ImageResult.State.UP_TO_DATE : ImageResult.State.OUTDATED,
                null);
    }

    /** The state, and why it could not be established when that is the answer. */
    public record ImageCheck(@NotNull ImageResult.State state, String reason) { }

    /**
     * Refused here, on purpose.
     *
     * <p>A recreate is {@code compose up --force-recreate --no-deps}, and doing it from an inspect
     * would mean rebuilding a container definition this service does not own. {@code steward-deployer}
     * owns it. This says so rather than doing something that would work until the day compose.yml
     * changed and then quietly create the old container for ever.</p>
     */
    @Override
    public @NotNull RedeployResult recreate(final @NotNull String service) {
        return RedeployResult.refused("recreating " + service + " is steward-deployer's: it has the "
                + "compose file, and a container rebuilt from an inspect would drift from it "
                + "silently. Not wired from here yet - see §8b.");
    }

    @Override
    public @NotNull BackupResult backup(final @NotNull String volume) {
        return BackupResult.refused("steward-worker takes its own snapshots (§9a) and that is not "
                + "built yet, so nothing was saved of " + volume + ". Refused rather than failed: "
                + "there is a difference between a snapshot that went wrong and one nobody has "
                + "written the code for, and a run must not proceed as if a volume were safe.");
    }

    @Override
    public @NotNull BackupResult backupState(final @NotNull String volume, final @NotNull String backupId) {
        return BackupResult.refused("no snapshot of " + volume + " was started, so there is no "
                + "state to read.");
    }

    private static String shortId(final String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }
}
