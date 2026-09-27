package eu.nordtal.s2.steward.worker.configfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Rewrites a top-level scalar or a sequence of scalars, changing only the characters of the value. */
final class ScalarWriter {

    private ScalarWriter() {}

    static String scalar(final List<String> lines, final ConfigEntry entry, final Span span, final String value) {
        final boolean multiLine = value.indexOf('\n') >= 0;
        if (multiLine && entry.type() != ConfigEntry.Type.STRING) {
            throw new IllegalArgumentException(entry.path() + " is a "
                    + entry.type().name().toLowerCase(Locale.ROOT)
                    + " in this file and cannot hold more than one line");
        }

        // Only the value's characters move, so a trailing comment stays untouched.
        if (!multiLine && !span.multiLine() && span.onKeyLine()) {
            final String rendered = Scalars.render(entry.type(), value, entry.path());
            lines.set(span.line(), LineEdits.splice(lines.get(span.line()), span, rendered));
            return entry.type() == ConfigEntry.Type.STRING ? value : rendered;
        }

        final String keyBody = LineEdits.withoutLineEnding(lines.get(span.keyLine()));
        final int colon = LineEdits.colonOf(keyBody, span, entry);
        final String prefix = keyBody.substring(0, colon + 1);
        final String comment = LineEdits.trailingComment(keyBody, span, colon);
        final int last = LineEdits.blockExtent(lines, span.keyLine(), span.keyColumn());
        final String inner = LineEdits.dominantEnding(lines);

        final List<String> replacement = new ArrayList<>();
        final String rendered;
        final Optional<Scalars.Block> block = multiLine ? Scalars.block(value) : Optional.empty();
        if (block.isPresent()) {
            rendered = value;
            replacement.add(prefix + " " + block.get().header() + comment + inner);
            final String indent = " ".repeat(span.keyColumn());
            for (final String line : block.get().lines()) {
                replacement.add(line.isEmpty() ? inner : indent + line + inner);
            }
        } else {
            // A value collapsing a block, or one no block could carry: double-quoted.
            final String written = Scalars.render(entry.type(), value, entry.path());
            rendered = entry.type() == ConfigEntry.Type.STRING ? value : written;
            replacement.add(prefix + " " + written + comment + inner);
        }
        LineEdits.keepEndingOf(replacement, lines.get(last));
        LineEdits.replaceLines(lines, span.keyLine(), last, replacement);
        return rendered;
    }

    static List<String> sequence(
            final List<String> lines, final ConfigEntry entry, final Span span, final List<String> items) {
        final List<String> rendered = new ArrayList<>(items.size());
        for (final String item : items) {
            rendered.add(Scalars.renderItem(entry.type(), item, entry.path(), span.flow()));
        }

        // A list written `[a, b]` stays written that way.
        if (span.flow()) {
            lines.set(
                    span.line(),
                    LineEdits.splice(lines.get(span.line()), span, "[" + String.join(", ", rendered) + "]"));
            return List.copyOf(items);
        }

        final String keyBody = LineEdits.withoutLineEnding(lines.get(span.keyLine()));
        final int colon = LineEdits.colonOf(keyBody, span, entry);
        final String comment = LineEdits.trailingComment(keyBody, span, colon);
        final int last = LineEdits.sequenceExtent(lines, span.keyLine(), span.start());
        final String inner = LineEdits.dominantEnding(lines);

        final List<String> replacement = new ArrayList<>();
        if (rendered.isEmpty()) {
            // `[]` means empty where a bare `key:` means null.
            replacement.add(keyBody.substring(0, colon + 1) + " []" + comment + inner);
        } else {
            replacement.add(keyBody.substring(0, colon + 1) + comment + inner);
            final String indent = " ".repeat(span.start());
            for (final String item : rendered) {
                replacement.add(indent + "- " + item + inner);
            }
        }
        LineEdits.keepEndingOf(replacement, lines.get(last));
        LineEdits.replaceLines(lines, span.keyLine(), last, replacement);
        return List.copyOf(items);
    }
}
