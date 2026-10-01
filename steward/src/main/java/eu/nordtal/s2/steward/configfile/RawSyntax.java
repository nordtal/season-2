package eu.nordtal.s2.steward.configfile;

import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.json.Json;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;

/**
 * The raw editor's save-time syntax check, which warns beside a save and never blocks it.
 *
 * TOML is not checked; properties only for a malformed unicode escape, the one thing it rejects.
 */
public final class RawSyntax {

    private RawSyntax() {}

    /** One thing this file's own format disagreed with the text about. */
    public record Warning(int line, String message) {

        /** {@code "Line N: message"}, or just the message when no line could be found. */
        public String sentence() {
            return line > 0 ? "Line " + line + ": " + message : message;
        }
    }

    /** The formats a raw editor knows, plus the fallback everything else gets. */
    public enum Format {
        YAML,
        JSON,
        TOML,
        PROPERTIES,
        TEXT
    }

    private static final Pattern BAD_UNICODE_ESCAPE = Pattern.compile("\\\\u(?![0-9a-fA-F]{4})");
    private static final Pattern LINE_FROM_GSON = Pattern.compile("line (\\d+)");

    /**
     * The format a raw editor draws for a file, decided by the last extension of its name.
     *
     * @param fileName the file's name or path, as {@link ConfigLocation#name()} carries it
     */
    public static Format formatOf(final String fileName) {
        final String lower = fileName.toLowerCase(Locale.ROOT);
        final int slash = lower.lastIndexOf('/');
        final String leaf = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (leaf.endsWith(".yml") || leaf.endsWith(".yaml")) {
            return Format.YAML;
        }
        if (leaf.endsWith(".json")) {
            return Format.JSON;
        }
        if (leaf.endsWith(".toml")) {
            return Format.TOML;
        }
        if (leaf.endsWith(".properties")) {
            return Format.PROPERTIES;
        }
        return Format.TEXT;
    }

    /**
     * Checks text against the format its file name says.
     *
     * @param fileName the file's own name, which picks the format
     * @param content the text as the operator has it, before it is written
     * @return a warning naming what looks wrong, or empty when nothing was found or the format is not checked
     */
    public static Optional<Warning> check(final String fileName, final String content) {
        return switch (formatOf(fileName)) {
            case YAML -> checkYaml(content);
            case JSON -> checkJson(content);
            case PROPERTIES -> checkProperties(content);
            case TOML, TEXT -> Optional.empty();
        };
    }

    /**
     * The two failures {@link ConfigFiles#read} reports: text that does not compose, and a root that is not a mapping.
     */
    private static Optional<Warning> checkYaml(final String content) {
        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (final MarkedYAMLException e) {
            final Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            final int line = mark == null ? 0 : mark.getLine() + 1;
            final String problem = e.getProblem() != null ? e.getProblem() : e.getMessage();
            return Optional.of(new Warning(line, "not valid YAML: " + problem));
        } catch (final YAMLException e) {
            return Optional.of(new Warning(0, "not valid YAML: " + e.getMessage()));
        }
        if (root == null) {
            // Empty or only comments, the same as an empty config file.
            return Optional.empty();
        }
        if (!(root instanceof MappingNode)) {
            return Optional.of(new Warning(
                    root.getStartMark().getLine() + 1,
                    "the top of the file must be a set of keys, found a " + root.getNodeId() + " instead"));
        }
        return Optional.empty();
    }

    private static Optional<Warning> checkJson(final String content) {
        if (content.isBlank()) {
            // Nothing typed is not a syntax error, as in ConfigFiles.parse.
            return Optional.empty();
        }
        try {
            final var _ = Json.tree(content);
            return Optional.empty();
        } catch (final JsonSyntaxException | IllegalStateException e) {
            final String message = e.getMessage() == null ? e.toString() : e.getMessage();
            final Matcher lineMatch = LINE_FROM_GSON.matcher(message);
            final int line = lineMatch.find() ? Integer.parseInt(lineMatch.group(1)) : 0;
            return Optional.of(new Warning(line, "not valid JSON: " + firstLineOf(message)));
        }
    }

    /** Gson's message without the troubleshooting URL it appends after a line break. */
    private static String firstLineOf(final String message) {
        final int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static Optional<Warning> checkProperties(final String content) {
        try {
            new Properties().load(new StringReader(content));
            return Optional.empty();
        } catch (final IOException | IllegalArgumentException e) {
            final String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (BAD_UNICODE_ESCAPE.matcher(lines[i]).find()) {
                    return Optional.of(new Warning(i + 1, "not valid properties: malformed \\uXXXX encoding"));
                }
            }
            // No line found for the failure, so none is named rather than a wrong one.
            final String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return Optional.of(new Warning(0, "not valid properties: " + message));
        }
    }
}
