package eu.nordtal.s2.steward;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.messages.MessageJson;
import eu.nordtal.s2.messages.spec.MessageSchema;
import eu.nordtal.s2.steward.texts.WebTexts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Writes the frontend's packaged texts and the values each key takes, from the bundles {@link WebTexts} serves.
 * Run by {@code :steward:generateApiTypes}; {@code TextTypesTest} fails while a committed file differs.
 */
public final class TextTypes {

    /** The values each key takes, which type a call of {@code t}. */
    static final Path TYPES = Path.of("frontend/src/lib/texts.gen.ts");

    /** The packaged English texts as parsed trees, and each value's declared kind, which a test renders. */
    static final Path TEXTS = Path.of("frontend/src/lib/texts.gen.json");

    private TextTypes() {}

    /** The TypeScript, one entry per key of every spec, sorted, as the JSON is. */
    static String types() {
        final StringBuilder out = new StringBuilder();
        out.append(
                "/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */\n");
        out.append("import type { Arg } from \"@/lib/texts\"\n\n");
        out.append("export type TextArgs = {\n");
        for (final MessageSchema.Entry entry : entries()) {
            out.append("  \"").append(entry.key()).append("\": ");
            if (entry.args().isEmpty()) {
                out.append("Record<string, never>\n");
                continue;
            }
            out.append("{\n");
            for (final MessageSchema.Arg arg : entry.args()) {
                if (arg.kind() == null) {
                    throw new IllegalStateException(entry.key() + ": Steward's texts take values, not "
                            + (arg.action() ? "an action" : "a context"));
                }
                final String name = Objects.requireNonNull(arg.name(), entry.key());
                out.append("    ")
                        .append(name.matches("[A-Za-z_$][\\w$]*") ? name : "\"" + name + "\"")
                        .append(": Arg[\"")
                        .append(arg.kind())
                        .append("\"]\n");
            }
            out.append("  }\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /** The JSON, one key per line, sorted, so a change to one text is a change to one line. */
    static String texts() {
        final Map<String, Object> kinds = new TreeMap<>();
        for (final MessageSchema.Entry entry : entries()) {
            if (!entry.args().isEmpty()) {
                final Map<String, String> declared = new LinkedHashMap<>();
                entry.args().forEach(arg -> declared.put(arg.name(), arg.kind()));
                kinds.put(entry.key(), declared);
            }
        }
        final Map<String, Object> texts = new TreeMap<>(MessageJson.texts(WebTexts.load(sources()), Locale.ENGLISH));
        return "{\n  \"kinds\": " + lines(kinds) + ",\n  \"texts\": " + lines(texts) + "\n}\n";
    }

    private static String lines(final Map<String, Object> entries) {
        final StringBuilder out = new StringBuilder("{\n");
        int left = entries.size();
        for (final Map.Entry<String, Object> entry : entries.entrySet()) {
            out.append("    \"").append(entry.getKey()).append("\": ").append(Json.encode(entry.getValue()));
            out.append(--left > 0 ? ",\n" : "\n");
        }
        return out.append("  }").toString();
    }

    /** Every key the page may name, sorted: the schema orders by the English file, which this may not see. */
    private static List<MessageSchema.Entry> entries() {
        return WebTexts.SPECS.stream()
                .flatMap(spec -> MessageSchema.entries(spec).stream())
                .sorted(Comparator.comparing(MessageSchema.Entry::key))
                .toList();
    }

    /** Steward's own resources from the source tree, since this runs on classes alone; the rest from the jars. */
    private static ClassLoader sources() {
        try {
            return new URLClassLoader(
                    new URL[] {Path.of("src/main/resources").toUri().toURL()}, TextTypes.class.getClassLoader());
        } catch (final MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(final String[] args) {
        try {
            Files.writeString(TYPES, types(), StandardCharsets.UTF_8);
            Files.writeString(TEXTS, texts(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
