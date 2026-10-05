package eu.nordtal.season.stewardagent.topology;

import com.google.gson.Gson;
import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.internalapi.agent.AgentWire;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the repository's own compose.yml declares, read through the agent's parser so no test carries a copy. */
public final class DeclaredTopology {

    private static final AgentWire.Topology TOPOLOGY =
            ComposeTopology.parse(new Gson().toJsonTree(rawServices()).getAsJsonObject(), "/backup-sources");

    private DeclaredTopology() {}

    /** What compose.yml's labels say; no mount is read, since only {@code compose config} writes them out in full. */
    public static AgentWire.Topology topology() {
        return TOPOLOGY;
    }

    private static Map<String, Object> rawServices() {
        final Map<String, Object> raw = new LinkedHashMap<>();
        ComposeFile.get().services().forEach((name, service) -> raw.put(name, service.raw()));
        return raw;
    }
}
