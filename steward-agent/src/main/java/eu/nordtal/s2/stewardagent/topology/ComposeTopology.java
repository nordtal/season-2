package eu.nordtal.s2.stewardagent.topology;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The topology: what compose.yml's labels say about each service, the one place any process learns it from.
 *
 * Read through {@code compose config}, so anchors and interpolation are resolved as a deployment resolves them.
 */
public final class ComposeTopology {

    /** The label that marks a service whose console can be typed into. */
    public static final String CONSOLE = "eu.nordtal.console";

    /** The label that marks a service a backup stops while it saves, with the value {@code stop}. */
    public static final String BACKUP = "eu.nordtal.backup";

    /** How long one reading is used; the file is baked into the image, only the environment file can change. */
    private static final Duration FRESH_FOR = Duration.ofMinutes(1);

    private final Definitions definitions;
    private final String backupSources;
    private final Clock clock;

    private @Nullable Reading last;

    private record Reading(AgentWire.Topology topology, Instant at) {}

    /** {@code compose config}'s {@code services} object across every profile; a test hands in its own. */
    @FunctionalInterface
    public interface Definitions {
        JsonObject read() throws IOException;
    }

    /**
     * @param backupSources where the agent's backup mounts sit, so every mount below it is a volume a backup saves
     */
    public ComposeTopology(final Definitions definitions, final String backupSources, final Clock clock) {
        this.definitions = definitions;
        this.backupSources = backupSources;
        this.clock = clock;
    }

    /** The topology as compose.yml says it now, read again once a minute. */
    public synchronized AgentWire.Topology read() {
        final Instant now = clock.instant();
        final Reading reading = last;
        if (reading != null && now.isBefore(reading.at().plus(FRESH_FOR))) {
            return reading.topology();
        }
        try {
            final AgentWire.Topology topology = parse(definitions.read(), backupSources);
            last = new Reading(topology, now);
            return topology;
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("compose.yml could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /** The services a line can be typed into. */
    public Set<String> consoles() {
        final Set<String> consoles = new LinkedHashSet<>();
        for (final AgentWire.Service service : read().services()) {
            if (service.console()) {
                consoles.add(service.name());
            }
        }
        return consoles;
    }

    /** The topology out of {@code compose config}'s {@code services} object. */
    static AgentWire.Topology parse(final JsonObject services, final String backupSources) {
        final String root = backupSources.endsWith("/") ? backupSources : backupSources + "/";
        final List<AgentWire.Service> all = new ArrayList<>();
        // Saved volume -> the source the agent mounts it from, so the services on the same source can be found.
        final Map<String, String> saved = new LinkedHashMap<>();
        for (final String name : services.keySet()) {
            final JsonObject service = services.getAsJsonObject(name);
            final JsonObject labels =
                    service.has("labels") && service.get("labels").isJsonObject()
                            ? service.getAsJsonObject("labels")
                            : new JsonObject();
            all.add(new AgentWire.Service(
                    name,
                    text(service, "image"),
                    "true".equals(text(labels, CONSOLE)),
                    "stop".equals(text(labels, BACKUP))));
            for (final JsonObject mount : mounts(service)) {
                final String target = text(mount, "target");
                if (target != null && target.startsWith(root) && target.length() > root.length()) {
                    saved.put(target.substring(root.length()), Objects.requireNonNullElse(text(mount, "source"), ""));
                }
            }
        }
        final Map<String, List<String>> mountedBy = new LinkedHashMap<>();
        saved.forEach((volume, source) -> mountedBy.put(volume, usersOf(services, source)));
        return new AgentWire.Topology(all, List.copyOf(saved.keySet()), mountedBy);
    }

    /** The services but the agent with a mount of {@code source}, in file order. */
    private static List<String> usersOf(final JsonObject services, final String source) {
        final List<String> users = new ArrayList<>();
        if (source.isEmpty()) {
            return users;
        }
        for (final String name : services.keySet()) {
            if (AgentWire.SERVICE.equals(name)) {
                continue;
            }
            if (mounts(services.getAsJsonObject(name)).stream()
                    .anyMatch(mount -> source.equals(text(mount, "source")))) {
                users.add(name);
            }
        }
        return users;
    }

    private static List<JsonObject> mounts(final JsonObject service) {
        final List<JsonObject> mounts = new ArrayList<>();
        if (service.has("volumes") && service.get("volumes").isJsonArray()) {
            for (final JsonElement mount : service.getAsJsonArray("volumes")) {
                if (mount.isJsonObject()) {
                    mounts.add(mount.getAsJsonObject());
                }
            }
        }
        return mounts;
    }

    private static @Nullable String text(final JsonObject json, final String key) {
        final JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
