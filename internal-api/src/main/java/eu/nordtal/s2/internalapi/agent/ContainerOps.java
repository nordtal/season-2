package eu.nordtal.s2.internalapi.agent;

/**
 * What an update sequence needs a container runtime to do, as the seam its tests fake.
 *
 * Creating a container is not on it: that needs the compose file, which {@code steward-agent} has.
 */
public interface ContainerOps {

    /** Every service of the project with its container id, status and health. */
    RuntimeResult runtime();

    /** Stops one container within the implementation's grace period, after which Docker kills it. */
    RedeployResult stop(String containerId);

    /** Starts one container again; {@link #runtime()} answers whether it is back. */
    RedeployResult start(String containerId);

    /** Which services run an image the registry has moved past, read before anything stops so the plan can show it. */
    ImageResult images();

    /**
     * Pulls one service's image and recreates its container from it; never asked for steward itself.
     *
     * @param service the compose service name, not a container id
     */
    RedeployResult deploy(String service);

    /**
     * Makes one service's container again from the image on this host, without pulling; also how a standby starts.
     *
     * @param service the compose service name
     */
    RedeployResult recreate(String service);
}
