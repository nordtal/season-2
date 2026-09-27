package eu.nordtal.s2.steward.worker.configfile;

import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/** Line-level primitives a config file writer works in: splicing a value into a line, finding a block's extent. */
final class LineEdits {

    private LineEdits() {}

    /** Replaces the value's characters on its line, leaving the indentation and any trailing comment. */
    static String splice(final String line, final Span span, final String rendered) {
        final String body = withoutLineEnding(line);
        final String ending = line.substring(body.length());
        final int start = Math.min(span.start(), body.length());
        final int end = Math.min(Math.max(span.end(), start), body.length());

        if (start == end) {
            // `token:` with nothing after it: the span sits right after the colon, so a space is inserted.
            final String before = body.substring(0, start);
            final String after = body.substring(start);
            return before + (before.endsWith(" ") ? "" : " ") + rendered + after + ending;
        }
        return body.substring(0, start) + rendered + body.substring(end) + ending;
    }

    /**
     * The colon that ends the key.
     *
     * Found by searching from the end of the key rather than the start of the line: a key may contain one, as in
     * {@code 12:00: something}.
     */
    static int colonOf(final String keyBody, final Span span, final ConfigEntry entry) {
        final int colon = keyBody.indexOf(':', Math.min(span.keyEndColumn(), keyBody.length()));
        if (colon < 0) {
            throw new IllegalStateException(
                    "Refusing to write " + entry.path() + ": line " + entry.line() + " has no colon after the key");
        }
        return colon;
    }

    /**
     * Whatever comment sits on the key's line after the value begins, so a rewrite does not eat it.
     *
     * Where the value begins depends on the shape: past the block header for {@code motd: |- # why}, past the value
     * for {@code port: 8080 # why}, and past the colon when the value is on a later line altogether.
     */
    static String trailingComment(final String keyBody, final Span span, final int colon) {
        final int from;
        if (!span.onKeyLine()) {
            from = colon + 1;
        } else if (span.block()) {
            from = endOfToken(keyBody, span.start());
        } else {
            from = span.end();
        }
        final String rest = keyBody.substring(Math.min(Math.max(from, 0), keyBody.length()));
        return rest.strip().startsWith("#") ? rest : "";
    }

    private static int endOfToken(final String body, final int from) {
        int i = Math.min(Math.max(from, 0), body.length());
        while (i < body.length() && !Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * The last line belonging to a key, measured by indentation.
     *
     * SnakeYAML's end mark for a block sits on the line after it, and how far after depends on what follows, so it
     * cannot be used to decide which lines to replace. Indentation can: everything under a key is indented past it,
     * a blank line belongs to the block only when something deeper follows it, and the first line back at the key's
     * own column ends it.
     */
    static int blockExtent(final List<String> lines, final int keyLine, final int keyColumn) {
        int last = keyLine;
        for (int i = keyLine + 1; i < lines.size(); i++) {
            final String text = withoutLineEnding(lines.get(i));
            if (text.isBlank()) {
                continue;
            }
            if (indentOf(text) <= keyColumn) {
                break;
            }
            last = i;
        }
        return last;
    }

    /**
     * The last line belonging to a block sequence.
     *
     * {@link #blockExtent} cannot answer this one: a block sequence is allowed to sit at its key's own column, so
     * "deeper than the key" finds nothing and a rewrite that trusted it would insert new entries above the old ones.
     *
     * @param itemIndent the column the first {@code -} sits at
     */
    static int sequenceExtent(final List<String> lines, final int keyLine, final int itemIndent) {
        int last = keyLine;
        for (int i = keyLine + 1; i < lines.size(); i++) {
            final String text = withoutLineEnding(lines.get(i));
            if (text.isBlank()) {
                continue;
            }
            final int indent = indentOf(text);
            if (indent > itemIndent) {
                last = i;
            } else if (indent == itemIndent && isEntry(text, indent)) {
                last = i;
            } else {
                break;
            }
        }
        return last;
    }

    /** Whether a line at this indentation is a {@code - entry} rather than the next key. */
    static boolean isEntry(final String text, final int indent) {
        return text.charAt(indent) == '-'
                && (text.length() == indent + 1 || Character.isWhitespace(text.charAt(indent + 1)));
    }

    static int indentOf(final String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** The line ending this file uses, taken from the first line that has one. */
    static String dominantEnding(final List<String> lines) {
        for (final String line : lines) {
            final String ending = line.substring(withoutLineEnding(line).length());
            if (!ending.isEmpty()) {
                return ending;
            }
        }
        return "\n";
    }

    /** Gives the last replacement line the ending the last replaced line had. */
    static void keepEndingOf(final List<String> replacement, final String replaced) {
        final String ending = replaced.substring(withoutLineEnding(replaced).length());
        final String lastLine = replacement.getLast();
        replacement.set(replacement.size() - 1, withoutLineEnding(lastLine) + ending);
    }

    static void replaceLines(final List<String> lines, final int from, final int to, final List<String> replacement) {
        lines.subList(from, to + 1).clear();
        lines.addAll(from, replacement);
    }

    /** Inserts {@code newLines} right after {@code lines.get(index)}, keeping the file's trailing newline style. */
    static void insertLinesAfter(final List<String> lines, final int index, final List<String> newLines) {
        if (index == lines.size() - 1) {
            final String last = lines.get(index);
            final String withoutEnding = withoutLineEnding(last);
            if (withoutEnding.length() == last.length() && !withoutEnding.isEmpty()) {
                // The old last line had no trailing newline; it needs one now that it is no longer last.
                lines.set(index, withoutEnding + dominantEnding(lines));
                final String newLast = newLines.get(newLines.size() - 1);
                newLines.set(newLines.size() - 1, withoutLineEnding(newLast));
            }
        }
        lines.addAll(index + 1, newLines);
    }

    /**
     * Splits into lines that still carry their own {@code \n} or {@code \r\n}.
     *
     * {@code String.lines()} throws the endings away, and joining with one of them afterwards is how a CRLF file
     * silently becomes an LF file and a whole config shows up in a diff.
     */
    static List<String> splitKeepingLineEndings(final String content) {
        final List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            final char c = content.charAt(i);
            if (c == '\n') {
                lines.add(content.substring(start, i + 1));
                start = i + 1;
            } else if (c == '\r') {
                final boolean crlf = i + 1 < content.length() && content.charAt(i + 1) == '\n';
                lines.add(content.substring(start, i + (crlf ? 2 : 1)));
                if (crlf) {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    static String withoutLineEnding(final String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    static int lineOf(final MarkedYAMLException e) {
        final Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
        return mark == null ? 1 : mark.getLine() + 1;
    }
}
