package eu.nordtal.s2.steward.worker.configfile;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
 * The raw editor's save-time syntax check (steward/60): a best-effort look at whether the text an
 * operator just typed still parses as the format its file name says it is, named by the line it
 * went wrong on when one can be found at all.
 *
 * <p><b>Never authoritative.</b> {@link ConfigFiles#writeRaw} writes exactly what it is given
 * whether or not this finds anything - Till's decision for this editor is that an editor which
 * refuses to save a file is one an operator has to work around. This class only ever produces a
 * sentence for the interface to show beside a save that already happened.</p>
 *
 * <p><b>TOML gets no check, on purpose.</b> Nothing else in this module or its dependencies parses
 * TOML - jcore, this worker and Paper all read YAML - and adding a parsing library for one warning
 * message is exactly the "too expensive" case the ticket names: no check at all is the honest
 * answer, because a hand-rolled one that disagrees with the real reason a TOML file fails would be
 * worse than none - the ticket's own preference is no warning at all over a wrong one.</p>
 *
 * <p><b>Properties gets one check, not a parser.</b> {@link Properties#load} is forgiving almost to
 * a fault - most text is a legal {@code .properties} file - so the one thing it can actually still
 * reject, a malformed unicode escape (a backslash-u not followed by four hex digits), is the one
 * thing checked here. The JDK does not say
 * which line it was on, so this class finds it again by hand once {@code load} has already thrown,
 * purely to be able to name one.</p>
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

    /** The four formats the ticket names, plus the fallback everything else gets. */
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
     * The format a raw editor draws for a file, decided the same way everywhere it is asked:
     * by the last extension on the file's own name, never by sniffing its content.
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
     * @param fileName the file's own name, used only to decide which format to check it as
     * @param content  the text as the operator has it right now, before it is written anywhere
     * @return a warning naming what looks wrong, or empty when the format is not checked
     *         ({@link Format#TOML}, {@link Format#TEXT}) or nothing was found
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
     * The same two failure shapes {@link ConfigFiles#read} itself reports: text SnakeYAML refuses
     * to compose at all, and a document that composes but is not a mapping at its root - the two
     * ways a {@code .yml} most often lands an operator in the raw editor to begin with.
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
            // Empty, or nothing but comments - the same as an empty config file, not an error.
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
            // Nothing typed is not a syntax error - the same call ConfigFiles.parse makes for an
            // empty YAML file.
            return Optional.empty();
        }
        try {
            JsonParser.parseString(content);
            return Optional.empty();
        } catch (final JsonSyntaxException | IllegalStateException e) {
            final String message = e.getMessage() == null ? e.toString() : e.getMessage();
            final Matcher lineMatch = LINE_FROM_GSON.matcher(message);
            final int line = lineMatch.find() ? Integer.parseInt(lineMatch.group(1)) : 0;
            return Optional.of(new Warning(line, "not valid JSON: " + firstLineOf(message)));
        }
    }

    /**
     * Gson's own message carries a troubleshooting URL after a line break; that is not something to
     * hand an operator as if this class wrote it.
     */
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
            // Properties.load threw for a reason this loop did not find a line for - keep the
            // sentence honest rather than pointing at a line that may not be the right one.
            final String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return Optional.of(new Warning(0, "not valid properties: " + message));
        }
    }
}
