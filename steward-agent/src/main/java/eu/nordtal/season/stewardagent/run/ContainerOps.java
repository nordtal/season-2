package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;

/**
 * What an update sequence needs a container runtime to do, as the seam its tests fake.
 *
 * Creating a container is not on it: that needs the compose file, which {@code steward-agent} has.
 */
public interface ContainerOps {

    /** What compose.yml says about every service, and what a backup saves and stops. */
    AgentWire.Topology topology();

    /** Every service of the project with its container id, status and health. */
    RuntimeResult runtime();

    /** Stops one container within the implementation's grace period, after which Docker kills it. */
    RedeployResult stop(String containerId);

    /** Starts one container again; {@link #runtime()} answers whether it is back. */
    RedeployResult start(String containerId);

    /** Which services run an image the registry has moved past, read before anything stops so the plan can show it. */
    ImageResult images();

    /**
     * Pulls one service's image and recreates its container from it; never asked for steward-agent itself.
     *
     * @param service the compose service name, not a container id
     */
    RedeployResult deploy(String service);

    /**
     * Makes one service's container again from the image on this host, without pulling.
     *
     * @param service the compose service name
     */
    RedeployResult recreate(String service);

    /**
     * Makes a standby's container from the image on this host, fetching that image only when it is missing.
     *
     * @param service the standby's compose service name, whose image a one-shot's compose file may name first
     */
    RedeployResult standby(String service);

    /** Runs the migrate service of this compose file and waits for it; triggered only on a current schema. */
    RedeployResult migrate();

    /** The name of the one-shot container a run of a newer release is handed to, which the run's row records. */
    String oneShot();

    /**
     * Starts the one-shot steward-agent at {@code release} on the claimed run {@code id}, and returns once it runs.
     *
     * @param release the version, without its {@code v}, whose image and compose file the one-shot is made from
     */
    RedeployResult handOver(long id, String release);

    /** Makes the long-running steward-agent again from this compose file, as a one-shot does last. */
    RedeployResult renewAgent();

    /**
     * Removes the images no container uses and the unused build cache, once an update has made its containers.
     *
     * @return what went, or why nothing did
     */
    Pruned pruneImages();

    /**
     * What a prune of the images removed.
     *
     * @param failure what Docker answered when it refused, {@code null} when it pruned
     */
    record Pruned(
            int images,
            long freedBytes,
            @org.jspecify.annotations.Nullable String failure) {}
}
