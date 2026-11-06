package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.internalapi.agent.Topology;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A plugin the stack no longer runs, whose jar and data folder an update run deletes from one service.
 *
 * @param artifact the name its install step shows
 * @param prefixes every jar filename prefix it was published under, as {@code JarName} splits it
 * @param folder its data folder in {@code plugins/}
 */
public record Retired(String service, String artifact, List<String> prefixes, String folder) {

    public Retired {
        prefixes = List.copyOf(prefixes);
    }

    /** Prism replaced CoreProtect, which has no build for this Minecraft version; none of its log is kept. */
    public static final List<Retired> ALL =
            List.of(new Retired(Topology.SMP, "coreprotect", List.of("CoreProtect-CE", "CoreProtect"), "CoreProtect"));

    /** Whether a jar's prefix is this plugin's, in any case. */
    public boolean owns(final @Nullable String prefix) {
        return prefix != null && prefixes.stream().anyMatch(prefix::equalsIgnoreCase);
    }

    /** The retired plugins of one service. */
    public static List<Retired> on(final String service) {
        return ALL.stream().filter(retired -> retired.service().equals(service)).toList();
    }
}
