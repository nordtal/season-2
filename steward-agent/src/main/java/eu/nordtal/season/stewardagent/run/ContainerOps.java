package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import java.util.List;
import java.util.Map;

/**
 * The container actions an update sequence needs. Tests fake it.
 *
 * Creating a container is not on it: that needs the compose file, which {@code steward-agent} has.
 */
public interface ContainerOps {

    AgentWire.Topology topology();

    RuntimeResult runtime();

    RedeployResult stop(String containerId);

    RedeployResult start(String containerId);

    /** Returns the last {@code count} lines of a container's log, oldest first, or none if it cannot be read. */
    List<String> lastLines(String containerId, int count);

    /**
     * Gives the user of one service ownership of everything the service mounts. A failure is logged.
     */
    void handOverMounts(String service);

    /** Returns the services whose image the registry has moved past. */
    ImageResult images();

    /**
     * Pulls one service's image and recreates its container from it; never asked for steward-agent itself.
     *
     * @param service the compose service name
     */
    RedeployResult deploy(String service);

    /**
     * Creates the container of one service from the local image without pulling.
     *
     * @param service the compose service name
     */
    RedeployResult recreate(String service);

    /**
     * Makes a standby's container from the image on this host, fetching that image only when it is missing.
     *
     * @param service the compose service name of the standby
     */
    RedeployResult standby(String service);

    /** Runs the migrate service of this compose file and waits for it. */
    RedeployResult migrate();

    /**
     * Returns the name of the one-shot container that runs a newer release, which the row of the run records.
     */
    String oneShot();

    /**
     * Starts the one-shot steward-agent at {@code release} on the claimed run {@code id}.
     *
     * @param release the version without its {@code v}
     */
    RedeployResult handOver(long id, String release);

    /** Creates the long-running steward-agent from this compose file. The one-shot does it last. */
    RedeployResult renewAgent();

    /** Deletes the images that no container uses and the unused build cache. */
    Pruned pruneImages();

    /**
     * The result of the prune.
     *
     * @param removed the removed images by id with their size, as far as Docker listed them
     * @param failure Docker's answer when it refused, {@code null} when it pruned
     */
    record Pruned(
            int images,
            Map<String, Long> removed,
            long freedBytes,
            @org.jspecify.annotations.Nullable String failure) {}
}
