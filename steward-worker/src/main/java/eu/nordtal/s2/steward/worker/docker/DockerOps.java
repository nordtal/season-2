package eu.nordtal.s2.steward.worker.docker;

import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.ops.ServiceRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * {@link ContainerOps} against the Docker daemon itself.
 *
 * <h2>Why the daemon and not a panel</h2>
 * This project read its container runtime and its image drift out of Arcane, a management panel,
 * until 2026-09-12. Arcane's image check never asked a registry: it compared what it had already
 * persisted, answered "up to date", and four releases ran behind while nothing said so.
 * The daemon can answer the question properly
 * ({@code GET /distribution/{ref}/json}), and it is on the other end of a socket this container
 * already needs for logs and the console - so the panel's read half was replaced by this class and
 * the panel itself was removed.
 *
 * <h2>What it still cannot do</h2>
 * <b>Creating containers.</b> {@link #deploy} and {@link #recreate} need the compose file, which lives in
 * {@code steward-deployer} (§8b), and this class refuses rather than improvising a container
 * definition out of an inspect. <b>Volume snapshots</b> are not on this interface at all any
 * more: saving a volume is steward-worker's own work (§9a), done with tar against read-only
 * mounts, and a container runtime is the wrong thing to ask for one.
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

    /**
     * Stops a container, and refuses if Docker had to kill it.
     *
     * <h2>A stop that ran out of time is not a stop</h2>
     * Docker sends SIGTERM, waits {@value #STOP_GRACE_SECONDS} seconds and then sends SIGKILL - and
     * the API call succeeds either way. Paper was measured shutting down in three seconds
     * ({@code deploy/README.md}), so there is room; but a server that ever needed longer would be
     * killed halfway through saving, and the next thing this sequence does is tar the world volume.
     * A backup taken over a half-written region file is worse than no backup: it is a file that
     * looks like a restore point.
     *
     * <p>So the container is inspected afterwards and exit code 137 - SIGKILL - is reported as a
     * refusal. The line is then {@code FAILED}, a run is settled {@code FAILED} the moment any line
     * is, and a failed run authorises no farm reset. Nothing here tries to be cleverer than that:
     * the world is already saved or it is not, and the only useful thing left is to stop calling it
     * a success.</p>
     *
     * <h2>And an inspect nobody could read is a third answer</h2>
     * {@linkplain RedeployResult#unverified Unverified}, not refused and not an ordinary success.
     * Refusing <em>here</em> would take the network down over an unreadable {@code inspect}, which
     * is a worse outcome than the one being guarded against - so the line stays {@code STOPPED}
     * with the reason beside it and this method stops the sequence at nothing. What the doubt does
     * instead is travel. {@code UpdateRun} collects it, marks every archive the run then writes,
     * and {@code Runner.settle} decides what it costs on the path being settled: a run that wrote
     * something while those servers were down - an archive, or jars in a {@code plugins/} directory
     * - is settled {@code FAILED} over it, and a restart, which wrote nothing, is told about it and
     * left {@code DONE}. That is the difference this method exists to keep open: a stop nobody
     * watched is neither a failure of this call nor a fact to swallow.
     */
    @Override
    public @NotNull RedeployResult stop(final @NotNull String containerId) {
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
            // The stop itself succeeded. Not knowing how it ended is not the same as knowing it
            // ended badly, and refusing here would take a network down over an unreadable inspect.
            // It is not an ordinary success either, so it says which of the two it is and the
            // backup taken after it carries the mark.
            log.warn("could not read how {} exited", shortId(containerId), e);
            return RedeployResult.unverified(shortId(containerId) + " was stopped, and how it ended"
                    + " could not be read back: " + e.getMessage());
        }
        return RedeployResult.triggered("stop asked for " + shortId(containerId));
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
     * the registry answers for the reference it was created from. Four outcomes, and the last two
     * are the ones that matter:</p>
     *
     * <ul>
     *   <li>the registry's digest is among the image's - {@code UP_TO_DATE}</li>
     *   <li>it is not - {@code OUTDATED}, and there is work</li>
     *   <li>the daemon's own record says this image was built here, never pulled - {@code LOCAL}.
     *       Neutral, not a fault: this host is ahead of the registry, not behind it (steward/75).
     *       The next real update run replaces it silently, because the updater only ever installs
     *       from a release.</li>
     *   <li>the question could not be asked or answered at all - {@code UNKNOWN} <b>and</b> a line
     *       in {@code unverifiable}. A registry that is down or unreachable answers nothing, and an
     *       image whose exact content the daemon no longer has on file (its tag was rebuilt locally
     *       while the container was only restarted, not recreated) cannot be identified either.
     *       Neither is "current", and reporting them as current is exactly the failure this check
     *       exists to end. Credentials <b>are</b> among the reasons, and that is new: measured
     *       against the registry on 2026-09-13, {@code discord-bot} and {@code minecraft} answer
     *       without authentication, but {@code steward-worker}, {@code steward-ui} and
     *       {@code steward-deployer} answer {@code 403} - the latter two because they are new
     *       modules, {@code steward-worker} because renaming {@code updater} created a new package
     *       under a new name. A package under an organisation is private on its first push, so
     *       until those three are set public they land here as {@code unverifiable}, which is the
     *       honest answer and not a wrong one. All three were set public on 2026-09-13.</li>
     * </ul>
     */
    @Override
    public @NotNull ImageResult images() {
        try {
            final Map<String, ImageResult.State> states = new HashMap<>();
            final Set<String> unverifiable = new LinkedHashSet<>();
            // One answer per image, not per container. The four Minecraft services run the same
            // `ghcr.io/nordtal/minecraft:latest` from the same image id, and asking the registry
            // four times for one answer is four round trips - measured at ~250 ms each on this host
            // on 2026-09-13 - on a call the start page makes every time it refreshes.
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
                    // `docker ps` falls back to a bare short id once the tag a container was
                    // created from has been retagged onto another image - a local rebuild while the
                    // container itself was only `restart`ed, not `--force-recreate`d (discord-bot,
                    // measured on this host on 2026-09-16). The friendlier name compose created it
                    // with is still on the container's own Config, so ask for that instead of
                    // reporting on a hash nobody can act on.
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
            return ImageResult.unreachable(e.getMessage());
        }
    }

    /**
     * Whether {@code reference} is the bare short id {@code docker ps} prints once a container's
     * image no longer has a name - never a real {@code repo[:tag]}, because those carry a slash or a
     * colon and this is exactly the twelve lowercase hex characters at the front of {@code imageId}.
     */
    private static boolean isOrphanedShortId(final @NotNull String reference,
                                             final @Nullable String imageId) {
        return imageId != null && imageId.startsWith("sha256:") && reference.length() == 12
                && !reference.contains("/") && !reference.contains(":")
                && imageId.regionMatches(true, "sha256:".length(), reference, 0, reference.length());
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
        final Optional<Docker.ImageIdentity> identity = docker.imageIdentity(imageId);
        if (identity.isEmpty()) {
            // The exact bits this container was created from are gone from the daemon's store.
            // What its tag currently means is still worth asking - not proof of what THIS
            // container runs, but often the reason it is unreadable in the first place.
            final Optional<Docker.ImageIdentity> current = docker.imageIdentity(reference);
            if (current.isPresent() && current.get().builtLocally()) {
                return new ImageCheck(ImageResult.State.LOCAL,
                        reference + " no longer matches what this container runs: its tag was"
                                + " rebuilt locally while the container was only restarted, not"
                                + " recreated. The exact image this container runs cannot be read"
                                + " any more, and a --force-recreate (or a real update run) replaces"
                                + " it with that local build");
            }
            return new ImageCheck(ImageResult.State.UNKNOWN,
                    reference + "'s image no longer exists in this daemon's store, and what its tag"
                            + " currently means could not be read either");
        }
        if (identity.get().builtLocally()) {
            return new ImageCheck(ImageResult.State.LOCAL,
                    reference + " was built on this host and never published - the next real update"
                            + " run replaces it silently");
        }
        final List<String> local = identity.get().repoDigests();
        if (local.isEmpty()) {
            // No Identity.Build and no digest at all: what "built here, never pushed" looked like
            // under the classic graphdriver, before Identity existed to say so more directly.
            return new ImageCheck(ImageResult.State.LOCAL,
                    reference + " carries no registry digest and was not pulled either - built on"
                            + " this host and never published, the next real update run replaces it"
                            + " silently");
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
    public @NotNull RedeployResult deploy(final @NotNull String service) {
        return refusal("deploying", service);
    }

    @Override
    public @NotNull RedeployResult recreate(final @NotNull String service) {
        return refusal("recreating", service);
    }

    /**
     * The same refusal for both, because the reason is the same one and it is not about pulling.
     *
     * <p>Neither can be done from here at all: creating a container needs the compose file, and the
     * service that has it is steward-deployer. {@code DeployerRecreate} decorates this class and
     * answers both over HTTP; what is left here is the honest "no" for a deployment wired without
     * it.</p>
     */
    private static RedeployResult refusal(final String verb, final String service) {
        return RedeployResult.refused(verb + " " + service + " is steward-deployer's: it has the "
                + "compose file, and a container rebuilt from an inspect would drift from it "
                + "silently. Not wired from here yet - see §8b.");
    }

    private static String shortId(final String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }
}
