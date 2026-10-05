package eu.nordtal.season.common;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * The repository's own {@code compose.yml}, parsed once, with typed views so no test casts around SnakeYAML.
 *
 * Anchors and merge keys are resolved, nothing is interpolated; a test declares it in {@code repositoryRootTestInputs}.
 */
public final class ComposeFile {

    private static final Pattern DEFAULTED = Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$");

    private static final ComposeFile INSTANCE = read();

    private final String text;
    private final Map<String, Object> root;
    private final Map<String, Service> services = new LinkedHashMap<>();

    private ComposeFile(final String text, final Map<String, Object> root) {
        this.text = text;
        this.root = root;
        for (final Map.Entry<String, Object> entry : mapOf(root.get("services")).entrySet()) {
            services.put(entry.getKey(), new Service(entry.getKey(), mapOf(entry.getValue())));
        }
        if (services.isEmpty()) {
            throw new IllegalStateException("compose.yml has no services block");
        }
    }

    /** Returns the file, read on first use. */
    public static ComposeFile get() {
        return INSTANCE;
    }

    private static ComposeFile read() {
        final String text = RepositoryRoot.read("compose.yml");
        try (Reader reader = Files.newBufferedReader(RepositoryRoot.resolve("compose.yml"), StandardCharsets.UTF_8)) {
            return new ComposeFile(text, mapOf(new Yaml().load(reader)));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** Returns the file as written, for a test that asserts on a line of it. */
    public String text() {
        return text;
    }

    /** Returns the top-level {@code name:}, empty when the file sets none. */
    public Optional<String> projectName() {
        return Optional.ofNullable(root.get("name")).map(String::valueOf);
    }

    /** Returns the services in the order they are written. */
    public Map<String, Service> services() {
        return services;
    }

    /** Returns the service called {@code name}, failing the test when the file has none. */
    public Service service(final String name) {
        final Service found = services.get(name);
        if (found == null) {
            throw new AssertionError("compose.yml has no service '" + name + "'");
        }
        return found;
    }

    /** Returns a top-level block such as {@code volumes} or {@code configs}, empty when the file has none. */
    public Map<String, Object> block(final String name) {
        return mapOf(root.get(name));
    }

    /** Returns the text of the entry {@code name} under {@code configs}, where a service's file is written inline. */
    public String configContent(final String name) {
        final Object one = block("configs").get(name);
        if (!(one instanceof Map<?, ?> config) || config.get("content") == null) {
            throw new AssertionError("compose.yml has no config '" + name + "' with inline content");
        }
        return String.valueOf(config.get("content"));
    }

    /** Returns what compose falls back to for {@code ${NAME:-default}}, failing when {@code value} has no default. */
    public static String defaultOf(final String value) {
        final Matcher matcher = DEFAULTED.matcher(value);
        if (!matcher.matches()) {
            throw new AssertionError(value + " has no default an unfilled .env would fall back to");
        }
        return matcher.group(1);
    }

    /** Splits a {@code bind:host:container} mapping on its separating colons, not on the ones inside a default. */
    public static List<String> fields(final String mapping) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < mapping.length(); i++) {
            final char c = mapping.charAt(i);
            if (c == '$' && i + 1 < mapping.length() && mapping.charAt(i + 1) == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ':' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return List.copyOf(parts);
    }

    /** Returns the host side of a compose mount, everything before the last colon-separated field pair. */
    public static String sourceOf(final String mount) {
        return mount.substring(0, mount.lastIndexOf(':'));
    }

    /** Returns {@code value} as a map keyed by text, empty when it is no map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(final Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** One service of the file; every view is empty, never null, when the service does not set the key. */
    public record Service(String name, Map<String, Object> raw) {

        /** Returns whether the service sets {@code key} at all. */
        public boolean has(final String key) {
            return raw.containsKey(key);
        }

        /** Returns the value of {@code key} as text, empty when the service does not set it. */
        public Optional<String> text(final String key) {
            return Optional.ofNullable(raw.get(key)).map(String::valueOf);
        }

        /** Returns the nested block {@code key}, empty when the service does not set it. */
        public Map<String, Object> block(final String key) {
            return mapOf(raw.get(key));
        }

        /** Returns the entries of the list {@code key} as text, empty when the service does not set it. */
        public List<String> list(final String key) {
            return raw.get(key) instanceof List<?> entries
                    ? entries.stream().map(String::valueOf).toList()
                    : List.of();
        }

        /** Returns the environment, whether written as a map or as {@code KEY=value} lines. */
        public Map<String, String> environment() {
            return keyed("environment");
        }

        /** Returns what an unfilled {@code .env} leaves for {@code key}, failing the test when it has no default. */
        public String defaultedEnvironment(final String key) {
            final String value = environment().get(key);
            if (value == null) {
                throw new AssertionError("compose.yml sets no " + name + "." + key);
            }
            return defaultOf(value);
        }

        /** Returns the labels, whether written as a map or as {@code key=value} lines. */
        public Map<String, String> labels() {
            return keyed("labels");
        }

        /** Returns the profiles the service belongs to. */
        public List<String> profiles() {
            return list("profiles");
        }

        /** Returns the {@code ports} entries as written. */
        public List<String> ports() {
            return list("ports");
        }

        /** Returns the published ports that are UDP. */
        public List<String> udpPorts() {
            return ports().stream().filter(port -> port.endsWith("/udp")).toList();
        }

        /** Returns the {@code volumes} entries as written, the mounts of the service. */
        public List<String> mounts() {
            return list("volumes");
        }

        /** Returns the mount whose container side ends in {@code ending}, failing the test when there is none. */
        public String mountEndingIn(final String ending) {
            return mounts().stream()
                    .filter(mount -> mount.endsWith(ending))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no mount ending in " + ending + " on " + name));
        }

        /** Returns the names of the services this one waits for, whether written as a list or a map. */
        public List<String> dependsOn() {
            return raw.get("depends_on") instanceof List<?>
                    ? list("depends_on")
                    : List.copyOf(block("depends_on").keySet());
        }

        /** Returns the condition under which this service waits for {@code other}, empty when it does not wait. */
        public Optional<String> dependencyCondition(final String other) {
            return Optional.ofNullable(mapOf(block("depends_on").get(other)).get("condition"))
                    .map(String::valueOf);
        }

        /** Returns the networks the service joins, {@code default} when it names none. */
        public Set<String> networks() {
            if (!has("networks")) {
                return Set.of("default");
            }
            return raw.get("networks") instanceof List<?>
                    ? Set.copyOf(list("networks"))
                    : block("networks").keySet();
        }

        private Map<String, String> keyed(final String key) {
            final Map<String, String> entries = new LinkedHashMap<>();
            final Object written = raw.get(key);
            if (written instanceof List<?> lines) {
                for (final Object line : lines) {
                    final String entry = String.valueOf(line);
                    final int equals = entry.indexOf('=');
                    entries.put(
                            equals < 0 ? entry : entry.substring(0, equals),
                            equals < 0 ? "" : entry.substring(equals + 1));
                }
            } else {
                mapOf(written).forEach((name, value) -> entries.put(name, value == null ? "" : String.valueOf(value)));
            }
            return entries;
        }
    }

    /** Returns the path of the file, for a message. */
    public static Path path() {
        return RepositoryRoot.resolve("compose.yml");
    }
}
