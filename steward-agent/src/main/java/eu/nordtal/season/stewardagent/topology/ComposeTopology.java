package eu.nordtal.season.stewardagent.topology;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
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

    /** The label that makes a service a Minecraft server, its value the {@link Topology.Kind}'s Fill project. */
    public static final String SERVER = "eu.nordtal.server";

    /**
     * A server's plugins, space-separated as {@code artifact[=jar prefix][?]}, {@code ?} for one it may lack.
     *
     * The entrypoint's guard reads the same string as {@code SERVER_PLUGINS}, through a YAML alias.
     */
    public static final String PLUGINS = "eu.nordtal.plugins";

    /** The label that makes a service the standby of the one it names. */
    public static final String STANDBY_OF = "eu.nordtal.standby-of";

    /** The label that says when a run makes a service again: {@code run}, {@code after} or {@code last}. */
    public static final String RENEW = "eu.nordtal.renew";

    /** The heading the network page groups a service under; a service without it is not drawn. */
    public static final String SECTION = "eu.nordtal.section";

    /** The label that marks a service players reach from outside, with the value {@code true}. */
    public static final String ENTRY = "eu.nordtal.entry";

    /** The services a service sends requests to, space-separated. */
    public static final String REACHES = "eu.nordtal.reaches";

    /** The services a service keeps its data in, space-separated. */
    public static final String STORES_IN = "eu.nordtal.stores-in";

    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

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
                    "stop".equals(text(labels, BACKUP)),
                    server(name, labels),
                    text(labels, STANDBY_OF),
                    renewal(name, text(labels, RENEW)),
                    wiring(labels)));
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

    /** The server {@code name}'s labels describe, or none for a service without {@link #SERVER}. */
    private static Topology.@Nullable Service server(final String name, final JsonObject labels) {
        final String kind = text(labels, SERVER);
        if (kind == null) {
            return null;
        }
        final List<String> plugins = new ArrayList<>();
        final List<String> optional = new ArrayList<>();
        final Map<String, String> prefixes = new LinkedHashMap<>();
        final String listed =
                Objects.requireNonNullElse(text(labels, PLUGINS), "").strip();
        for (final String entry : listed.isEmpty() ? new String[0] : SPACES.split(listed)) {
            final boolean mayLack = entry.endsWith("?");
            final String plain = mayLack ? entry.substring(0, entry.length() - 1) : entry;
            final int equals = plain.indexOf('=');
            final String artifact = equals < 0 ? plain : plain.substring(0, equals);
            if (artifact.isEmpty() || (equals >= 0 && equals == plain.length() - 1)) {
                throw new IllegalStateException(name + "'s " + PLUGINS + " label has an entry '" + entry
                        + "' that is not artifact[=jar prefix][?]");
            }
            plugins.add(artifact);
            if (mayLack) {
                optional.add(artifact);
            }
            if (equals >= 0) {
                prefixes.put(artifact, plain.substring(equals + 1));
            }
        }
        try {
            return new Topology.Service(name, Topology.Kind.of(kind), plugins, optional, prefixes);
        } catch (final IllegalArgumentException unknown) {
            throw new IllegalStateException(name + "'s " + SERVER + " label: " + unknown.getMessage(), unknown);
        }
    }

    /** Where the network page draws a service, or none for one without {@link #SECTION}. */
    private static AgentWire.@Nullable Wiring wiring(final JsonObject labels) {
        final String section = text(labels, SECTION);
        if (section == null || section.isBlank()) {
            return null;
        }
        return new AgentWire.Wiring(
                section.strip(), "true".equals(text(labels, ENTRY)), names(labels, REACHES), names(labels, STORES_IN));
    }

    private static List<String> names(final JsonObject labels, final String label) {
        final String listed =
                Objects.requireNonNullElse(text(labels, label), "").strip();
        return listed.isEmpty() ? List.of() : List.of(SPACES.split(listed));
    }

    private static AgentWire.@Nullable Renewal renewal(final String name, final @Nullable String label) {
        if (label == null) {
            return null;
        }
        try {
            return AgentWire.Renewal.valueOf(label.toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    name + "'s " + RENEW + " label says '" + label + "', which is none of run, after and last",
                    unknown);
        }
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
