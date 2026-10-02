package eu.nordtal.s2.stewardagent.topology;

import com.google.gson.Gson;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** The repository's own compose.yml, read through the agent's parser, so no test carries a copy of the topology. */
public final class ComposeFile {

    private static final AgentWire.Topology TOPOLOGY =
            ComposeTopology.parse(new Gson().toJsonTree(services()).getAsJsonObject(), "/backup-sources");

    private ComposeFile() {}

    /** What compose.yml's labels say; no mount is read, since only {@code compose config} writes them out in full. */
    public static AgentWire.Topology topology() {
        return TOPOLOGY;
    }

    /** compose.yml's {@code services}, with anchors and merge keys resolved and nothing interpolated. */
    public static Map<String, Object> services() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> services = (Map<String, Object>) root.get("services");
            if (services == null) {
                throw new IllegalStateException(compose + " has no services block");
            }
            return services;
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Path findUpwards(final String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "could not find " + relative + " above " + Path.of("").toAbsolutePath());
    }
}
