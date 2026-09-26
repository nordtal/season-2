package eu.nordtal.s2.steward.worker.ops;

import org.jetbrains.annotations.NotNull;

/**
 * What an update sequence needs a container runtime to do, as a seam.
 *
 * <h2>One implementation, and it is still an interface</h2>
 * There were two until 2026-09-12 - one over an HTTP panel, one over the Docker socket - and only
 * {@code DockerOps} is left. The seam stays because it is what the tests fake: the sequence behind
 * these calls - read the runtime, stop each service, save a volume, start it again and watch until
 * it is healthy - is the part of this module with the most decisions in it and the least chance of
 * ever being rehearsed, so its ordering, its refusals and its timeout are held by tests against a
 * fake, and only the implementation touches a socket. Deleting the interface would mean testing
 * that ordering against a real daemon, which is not testing it.
 *
 * <h2>What is deliberately NOT on this interface</h2>
 * Creating a container. Doing it correctly needs the compose file, and the service that has the
 * compose file is {@code steward-deployer} (§8b). {@link #recreate} therefore asks somebody else
 * rather than doing it, and an implementation that cannot ask says so instead of improvising.
 */
public interface ContainerOps {

    /** Every service of the project with its container id, status and health. */
    @NotNull
    RuntimeResult runtime();

    /**
     * Stops one container.
     *
     * <p>The grace period is the implementation's, not {@code compose.yml}'s: {@code DockerOps}
     * asks for thirty seconds and Docker kills whatever is still running after them. Paper was
     * measured shutting down in three (deploy/README.md), so there is room - but a server that ever
     * needed longer to save would be killed, and that surfaces as a corrupt region file rather than
     * as an error here.</p>
     */
    @NotNull
    RedeployResult stop(@NotNull String containerId);

    /** Starts one container again. Started is not back - {@link #runtime()} answers that. */
    @NotNull
    RedeployResult start(@NotNull String containerId);

    /**
     * Which services are running an image the registry has moved past.
     *
     * <p>Read before anything is stopped, because an image update is a reason to take a server
     * down and therefore belongs in the plan a person confirms - not in a step discovered halfway
     * through a run that was counted down for something else.</p>
     */
    @NotNull
    ImageResult images();

    /**
     * Pulls one service's image and recreates its container from it.
     *
     * <p><b>This is not {@link #start}.</b> A start hands a stopped container back to Docker on
     * exactly the image it was created from, which is why an update of the jars alone never moves
     * an image: {@code entrypoint.sh}, the JRE underneath it and every change to {@code compose.yml}
     * stay on whatever was pulled at the last deploy. This is the one operation that changes that,
     * scoped to one service.</p>
     *
     * <p><b>Steward-worker must never ask this for itself.</b> The recreate takes the container
     * down, and this sequence is running inside it - the same rule as {@link #stop}, for a sharper
     * reason: a stop it survives because it never asks for one, and a recreate would end the run in
     * the middle with the servers already down.</p>
     *
     * @param service the compose service name, not a container id - it is resolved against the
     *                compose project, and a container id here names nothing
     */
    @NotNull
    RedeployResult deploy(@NotNull String service);

    /**
     * Makes one service's container again, <b>from the image already on this host</b>.
     *
     * <h2>The difference from {@link #deploy} is the whole of season-2-ops/134</h2>
     * Nothing is pulled here. "Recreate" means <em>make this container again</em>; "deploy" means
     * <em>fetch what is new</em>, and the two were one call until a measured run showed an admin's
     * Recreate button silently replacing a locally built image with the published one. An update
     * run wants the fetching one, because its reason to touch a container is that the registry has
     * moved. A <b>standby</b> wants this one, because its reason to exist is to be the same thing
     * as the service it stands in for - including the image, which on this deployment is very often
     * built on the host and published nowhere.
     *
     * <p>It is also how a standby is started at all: the standbys live in a compose profile that no
     * ordinary selection carries, so nothing brings them up on its own and naming one explicitly is
     * what enables its profile for that call (measured against Compose v5.5.1 on this host,
     * 2026-09-20).</p>
     *
     * @param service the compose service name
     */
    @NotNull
    RedeployResult recreate(@NotNull String service);
}
