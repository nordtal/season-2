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
import java.util.LinkedHashSet;
import java.util.List;
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

    /** How long one reading is used; the file is baked into the image, only the environment file can change. */
    private static final Duration FRESH_FOR = Duration.ofMinutes(1);

    private final Definitions definitions;
    private final Clock clock;

    private @Nullable Reading last;

    private record Reading(AgentWire.Topology topology, Instant at) {}

    /** {@code compose config}'s {@code services} object across every profile; a test hands in its own. */
    @FunctionalInterface
    public interface Definitions {
        JsonObject read() throws IOException;
    }

    public ComposeTopology(final Definitions definitions, final Clock clock) {
        this.definitions = definitions;
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
            final AgentWire.Topology topology = parse(definitions.read());
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
    static AgentWire.Topology parse(final JsonObject services) {
        final List<AgentWire.Service> all = new ArrayList<>();
        for (final String name : services.keySet()) {
            final JsonObject service = services.getAsJsonObject(name);
            final JsonObject labels =
                    service.has("labels") && service.get("labels").isJsonObject()
                            ? service.getAsJsonObject("labels")
                            : new JsonObject();
            all.add(new AgentWire.Service(name, text(service, "image"), "true".equals(text(labels, CONSOLE))));
        }
        return new AgentWire.Topology(List.copyOf(all));
    }

    private static @Nullable String text(final JsonObject json, final String key) {
        final JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
