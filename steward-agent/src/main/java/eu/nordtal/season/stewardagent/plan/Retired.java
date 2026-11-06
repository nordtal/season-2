package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.internalapi.agent.Topology;
import java.util.List;

/**
 * A plugin the stack no longer runs, whose jar and data folder an update run deletes from one service.
 *
 * @param artifact the name its install step shows
 * @param prefix the filename prefix of its jar, as {@link eu.nordtal.season.internalapi.agent.JarName} splits it
 * @param folder its data folder in {@code plugins/}
 */
public record Retired(String service, String artifact, String prefix, String folder) {

    /** Prism replaced CoreProtect, which has no build for this Minecraft version; none of its log is kept. */
    public static final List<Retired> ALL =
            List.of(new Retired(Topology.SMP, "coreprotect", "CoreProtect-CE", "CoreProtect"));

    /** The retired plugins of one service. */
    public static List<Retired> on(final String service) {
        return ALL.stream().filter(retired -> retired.service().equals(service)).toList();
    }
}
