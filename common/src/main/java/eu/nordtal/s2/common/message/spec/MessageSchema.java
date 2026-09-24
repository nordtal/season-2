package eu.nordtal.s2.common.message.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * What a {@link MessageSpec} says about its bundle, as data: every key with its name, its
 * placeholders and the names of the sections it sits in.
 *
 * <p>Written into the jar as {@code messages/<bundle>/schema.json} at build time (see
 * {@link #main(String[])}), which is where steward-worker reads it from, next to the texts it already
 * reads there. The order is the order of the English file, the one a person curated.</p>
 */
public final class MessageSchema {

    /** Adventure's component type, by name: a spec in a module without Adventure never loads it. */
    private static final String COMPONENT = "net.kyori.adventure.text.Component";

    private MessageSchema() {
    }

    /**
     * @param name      the placeholder
     * @param component whether it is filled by a Component, written {@code <name>} in the text
     */
    public record Arg(String name, boolean component) {
    }

    /**
     * @param key         the bundle key
     * @param name        the name an admin reads
     * @param description a sentence for a hard case, or {@code null}
     * @param args        the placeholders, in parameter order
     * @param section     the names of the sections around it, outermost first
     */
    public record Entry(String key, String name, String description, List<Arg> args, List<String> section) {
    }

    /** @return the bundle a spec describes */
    public static String bundle(final Class<?> spec) {
        final MessageSpec annotation = spec.getAnnotation(MessageSpec.class);
        if (annotation == null) {
            throw new IllegalArgumentException(spec.getName() + " is not a @MessageSpec interface");
        }
        return annotation.value();
    }

    /** @return every key the spec declares, in the order of its English file */
    public static List<Entry> entries(final Class<?> spec) {
        final List<Entry> entries = new ArrayList<>();
        walk(spec, "", List.of(), entries, 0);
        final Map<String, Integer> order = new LinkedHashMap<>();
        for (final String key : fileOrder(spec)) {
            order.putIfAbsent(key, order.size());
        }
        entries.sort(Comparator.comparingInt((Entry entry) -> order.getOrDefault(entry.key(), Integer.MAX_VALUE))
                .thenComparing(Entry::key));
        return entries;
    }

    private static void walk(final Class<?> type, final String prefix, final List<String> section,
                             final List<Entry> into, final int depth) {
        if (depth > 16) {
            throw new IllegalStateException(type.getName() + " nests sections more than 16 deep - a cycle?");
        }
        for (final Method method : type.getMethods()) {
            if (MessageSpecs.isSection(method)) {
                final List<String> inner = new ArrayList<>(section);
                inner.add(sectionName(method));
                walk(method.getReturnType(), prefix + MessageSpecs.segment(method) + ".", inner, into, depth + 1);
            } else if (MessageSpecs.isKey(method)) {
                final Name name = method.getAnnotation(Name.class);
                final Describe describe = method.getAnnotation(Describe.class);
                final List<Arg> args = new ArrayList<>();
                for (final Parameter parameter : method.getParameters()) {
                    final eu.nordtal.s2.common.message.spec.Arg arg =
                            parameter.getAnnotation(eu.nordtal.s2.common.message.spec.Arg.class);
                    args.add(new Arg(arg == null ? null : arg.value(),
                            COMPONENT.equals(parameter.getType().getName())));
                }
                into.add(new Entry(prefix + MessageSpecs.segment(method), name == null ? null : name.value(),
                        describe == null ? null : describe.value(), List.copyOf(args), List.copyOf(section)));
            }
        }
    }

    /** A section's name: on the method that opens it, or on its interface. */
    static String sectionName(final Method method) {
        final Name own = method.getAnnotation(Name.class);
        if (own != null) {
            return own.value();
        }
        final Name type = method.getReturnType().getAnnotation(Name.class);
        return type == null ? null : type.value();
    }

    static Properties english(final Class<?> spec) {
        return bundleFile(spec, "en");
    }

    static Properties bundleFile(final Class<?> spec, final String language) {
        final String resource = "messages/" + bundle(spec) + "/" + language + ".properties";
        final Properties properties = new Properties();
        try (InputStream in = spec.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return properties;
    }

    /** The keys of the English file in the order they are written. */
    private static List<String> fileOrder(final Class<?> spec) {
        final String resource = "messages/" + bundle(spec) + "/en.properties";
        final List<String> keys = new ArrayList<>();
        try (InputStream in = spec.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return keys;
            }
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            boolean continued = false;
            for (final String line : text.split("\n")) {
                final String trimmed = line.strip();
                final boolean wasContinued = continued;
                continued = trimmed.endsWith("\\") && !trimmed.endsWith("\\\\");
                if (wasContinued || trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                final int end = firstSeparator(trimmed);
                keys.add(trimmed.substring(0, end).strip());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return keys;
    }

    private static int firstSeparator(final String line) {
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '=' || c == ':' || c == ' ') {
                return i;
            }
        }
        return line.length();
    }

    /** @return the schema as the JSON steward-worker reads */
    public static String json(final Class<?> spec) {
        final StringBuilder out = new StringBuilder(4096);
        out.append("{\n  \"bundle\": ").append(quote(bundle(spec))).append(",\n  \"messages\": [");
        final List<Entry> entries = entries(spec);
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"key\": ").append(quote(entry.key()))
                    .append(", \"name\": ").append(quote(entry.name()));
            if (entry.description() != null) {
                out.append(", \"description\": ").append(quote(entry.description()));
            }
            out.append(", \"args\": [");
            for (int a = 0; a < entry.args().size(); a++) {
                final Arg arg = entry.args().get(a);
                out.append(a == 0 ? "" : ", ").append("{\"name\": ").append(quote(arg.name()))
                        .append(", \"component\": ").append(arg.component()).append('}');
            }
            out.append("], \"section\": [");
            for (int s = 0; s < entry.section().size(); s++) {
                out.append(s == 0 ? "" : ", ").append(quote(entry.section().get(s)));
            }
            out.append("]}");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static String quote(final String value) {
        if (value == null) {
            return "null";
        }
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * The build step: {@code <spec class> <output directory>} writes
     * {@code <output directory>/messages/<bundle>/schema.json}, and refuses a spec that
     * {@link MessageSpecCheck} finds fault with - a schema that disagrees with its bundle would
     * mislead the one screen that reads it.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: MessageSchema <spec class> <output directory>");
        }
        final Class<?> spec = Class.forName(args[0]);
        final List<String> problems = MessageSpecCheck.problems(spec);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(spec.getName() + " disagrees with its bundle:\n  "
                    + String.join("\n  ", problems));
        }
        final Path file = Path.of(args[1], "messages", bundle(spec), "schema.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(spec), StandardCharsets.UTF_8);
    }

    /** Placeholder names by kind, for {@link MessageSpecCheck}. */
    static Map<Boolean, List<String>> argsByKind(final Entry entry) {
        final Map<Boolean, List<String>> byKind = new LinkedHashMap<>();
        byKind.put(false, new ArrayList<>());
        byKind.put(true, new ArrayList<>());
        entry.args().forEach(arg -> byKind.get(arg.component()).add(arg.name()));
        return byKind;
    }
}
