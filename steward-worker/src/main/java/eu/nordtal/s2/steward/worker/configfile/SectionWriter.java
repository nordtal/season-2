package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites only the fields of a {@link Kind#SECTIONS} entry that changed, leaving every comment and key in place.
 *
 * A save may add or remove at most one entry of a list; a larger diff is refused rather than guessed at.
 */
final class SectionWriter {

    private SectionWriter() {}

    private record PendingEdit(ConfigEntry field, Object value) {}

    private record Columns(int dashColumn, int fieldColumn, boolean blankLineBefore) {}

    static List<Map<String, Object>> sections(
            final Parsed parsed,
            final List<String> lines,
            final ConfigEntry entry,
            final Span span,
            final List<Map<String, Object>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();
        if (incoming.size() == existing.size() + 1) {
            return appendSection(parsed, lines, entry, span, incoming);
        }
        if (incoming.size() == existing.size() - 1) {
            return removeSection(parsed, lines, entry, span, incoming);
        }
        if (incoming.size() != existing.size()) {
            throw new IllegalArgumentException(entry.path() + " has " + existing.size()
                    + " entr" + (existing.size() == 1 ? "y" : "ies") + " in the file right now, but "
                    + incoming.size() + " " + (incoming.size() == 1 ? "was" : "were")
                    + " sent - this editor adds or removes exactly one entry per save, not "
                    + Math.abs(incoming.size() - existing.size()) + " at once; save one change at a time");
        }
        final List<PendingEdit> edits = collectEdits(entry, existing, incoming);
        final Map<String, Object> rendered = applyEdits(parsed, lines, edits);
        return writtenRows(existing, rendered);
    }

    private static List<PendingEdit> collectEdits(
            final ConfigEntry entry, final List<List<ConfigEntry>> existing, final List<Map<String, Object>> incoming) {
        final List<PendingEdit> edits = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            final Map<String, Object> wanted = incoming.get(index);
            for (final ConfigEntry field : existing.get(index)) {
                final Object wantedValue = wanted.get(field.key());
                if (wantedValue == null) {
                    throw new IllegalArgumentException(field.path() + " is missing from entry " + index + " of "
                            + entry.path() + " that was sent to be saved");
                }
                if (field.kind() == Kind.MAP || !field.editable()) {
                    throw new IllegalArgumentException(field.path() + " (line " + field.line()
                            + ") is not a value or a list and cannot be changed through " + entry.path());
                }
                if (keepsSecret(field, wantedValue)) {
                    continue;
                }
                final Object normalised = shapedFor(field, wantedValue);
                if (!normalised.equals(valueOf(field))) {
                    edits.add(new PendingEdit(field, normalised));
                }
            }
        }
        return edits;
    }

    private static Map<String, Object> applyEdits(
            final Parsed parsed, final List<String> lines, final List<PendingEdit> edits) {
        // Bottom upwards: a field rewritten as a block shifts every span below it.
        final List<PendingEdit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(
                        (final PendingEdit edit) -> spanOf(parsed, edit.field()).keyLine())
                .reversed());
        final Map<String, Object> rendered = new HashMap<>();
        for (final PendingEdit edit : ordered) {
            final ConfigEntry field = edit.field();
            final Span fieldSpan = spanOf(parsed, field);
            rendered.put(
                    field.path(),
                    switch (field.kind()) {
                        case SCALAR -> ScalarWriter.scalar(lines, field, fieldSpan, (String) edit.value());
                        case LIST -> ScalarWriter.sequence(lines, field, fieldSpan, itemsOf(field, edit.value()));
                        case SECTIONS -> sections(parsed, lines, field, fieldSpan, recordsOf(field, edit.value()));
                        case MAP -> throw new IllegalStateException("unreachable: " + field.path());
                    });
        }
        return rendered;
    }

    private static List<Map<String, Object>> writtenRows(
            final List<List<ConfigEntry>> existing, final Map<String, Object> rendered) {
        final List<Map<String, Object>> written = new ArrayList<>(existing.size());
        for (final List<ConfigEntry> fields : existing) {
            final Map<String, Object> row = new LinkedHashMap<>();
            for (final ConfigEntry field : fields) {
                row.put(field.key(), rendered.getOrDefault(field.path(), valueOf(field)));
            }
            written.add(Collections.unmodifiableMap(row));
        }
        return written;
    }

    private static Span spanOf(final Parsed parsed, final ConfigEntry field) {
        return Objects.requireNonNull(
                parsed.spans().get(field.path()), "collect() puts a span for every entry it adds");
    }

    /** Whether {@code value} is a secret sent back empty, which means unchanged: the browser never had its value. */
    private static boolean keepsSecret(final ConfigEntry field, final Object value) {
        return field.secret() && ("".equals(value) || (value instanceof List<?> list && list.isEmpty()));
    }

    /** A sent value in the shape {@code field} holds (string, list of strings or list of records), or a refusal. */
    private static Object shapedFor(final ConfigEntry field, final Object value) {
        return switch (field.kind()) {
            case SCALAR -> {
                if (value instanceof String text) {
                    yield text;
                }
                throw new IllegalArgumentException(
                        field.path() + " is a single value (line " + field.line() + "): send one value, not a list");
            }
            case LIST -> itemsOf(field, value);
            case SECTIONS -> recordsOf(field, value);
            case MAP ->
                throw new IllegalArgumentException(
                        field.path() + " is a nested section (line " + field.line() + ") and has no value of its own");
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> itemsOf(final ConfigEntry field, final Object value) {
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return (List<String>) list;
        }
        throw new IllegalArgumentException(
                field.path() + " is a list (line " + field.line() + "): send its entries as plain values");
    }

    /** An empty list is an empty list of sections: JSON's {@code []} does not say which it is. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recordsOf(final ConfigEntry field, final Object value) {
        if (value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            return (List<Map<String, Object>>) list;
        }
        throw new IllegalArgumentException(field.path() + " is a list of sections (line " + field.line()
                + "): send one record per entry, not plain values");
    }

    /** What a field reads as, in the shape {@link #shapedFor} compares against. */
    private static Object valueOf(final ConfigEntry field) {
        return switch (field.kind()) {
            case SCALAR, MAP -> field.value();
            case LIST -> field.items();
            case SECTIONS -> sectionValuesOf(field);
        };
    }

    /** The fields a new entry is written with: the last entry's, else the schema's template, else empty. */
    private static List<ConfigEntry> shapeOf(final ConfigEntry entry) {
        if (!entry.sections().isEmpty()) {
            return entry.sections().getLast();
        }
        return entry.template();
    }

    /**
     * Appends one new entry to a {@link Kind#SECTIONS} list, refusing anything but a pure append.
     *
     * The entry copies the last entry's shape (not the first's, which may carry a comment), or the schema's template.
     */
    private static List<Map<String, Object>> appendSection(
            final Parsed parsed,
            final List<String> lines,
            final ConfigEntry entry,
            final Span span,
            final List<Map<String, Object>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();
        final List<ConfigEntry> shape = shapeOf(entry);
        if (shape.isEmpty()) {
            throw new IllegalArgumentException(entry.path() + " (line " + entry.line() + ") has no"
                    + " entry yet to copy the shape of a new one from - add the first entry by hand");
        }
        if (span.flow() && !existing.isEmpty()) {
            throw new IllegalArgumentException(entry.path() + " (line " + entry.line() + ") is"
                    + " written as a flow sequence: adding an entry is not supported for that style -"
                    + " write it as a block sequence by hand first");
        }
        validateAppendUnchanged(entry, existing, incoming);

        final Columns columns = columnsForAppend(parsed, lines, span, existing);
        final String inner = LineEdits.dominantEnding(lines);
        final List<String> newLines = new ArrayList<>();
        if (columns.blankLineBefore()) {
            newLines.add(inner);
        }
        final Map<String, Object> newRow = renderSection(
                shape, incoming.getLast(), columns.dashColumn(), columns.fieldColumn(), entry.path(), inner, newLines);
        insertNewEntryLines(lines, span, entry, existing, newLines);

        final List<Map<String, Object>> written = new ArrayList<>(existing.size() + 1);
        for (final List<ConfigEntry> fields : existing) {
            written.add(rowOf(fields));
        }
        written.add(newRow);
        return List.copyOf(written);
    }

    private static void validateAppendUnchanged(
            final ConfigEntry entry, final List<List<ConfigEntry>> existing, final List<Map<String, Object>> incoming) {
        for (int index = 0; index < existing.size(); index++) {
            final Map<String, Object> wanted = incoming.get(index);
            for (final ConfigEntry field : existing.get(index)) {
                final Object wantedValue = wanted.get(field.key());
                if (wantedValue == null) {
                    throw new IllegalArgumentException(field.path() + " is missing from entry " + index + " of "
                            + entry.path() + " that was sent to be saved");
                }
                if (!keepsSecret(field, wantedValue)
                        && !shapedFor(field, wantedValue).equals(valueOf(field))) {
                    throw new IllegalArgumentException(entry.path() + ": adding an entry cannot also"
                            + " change " + field.path() + " in the same save - save that change"
                            + " first, then add the entry");
                }
            }
        }
    }

    private static Columns columnsForAppend(
            final Parsed parsed, final List<String> lines, final Span span, final List<List<ConfigEntry>> existing) {
        if (existing.isEmpty()) {
            return new Columns(span.keyColumn(), span.keyColumn() + 2, false);
        }
        final List<ConfigEntry> last = existing.getLast();
        final Span firstFieldSpan = spanOf(parsed, last.getFirst());
        final int dashColumn = span.start();
        final int fieldColumn = last.size() > 1 ? spanOf(parsed, last.get(1)).keyColumn() : dashColumn + 2;
        final boolean blankLineBefore = firstFieldSpan.keyLine() > 0
                && LineEdits.withoutLineEnding(lines.get(firstFieldSpan.keyLine() - 1))
                        .isBlank();
        return new Columns(dashColumn, fieldColumn, blankLineBefore);
    }

    private static void insertNewEntryLines(
            final List<String> lines,
            final Span span,
            final ConfigEntry entry,
            final List<List<ConfigEntry>> existing,
            final List<String> newLines) {
        if (existing.isEmpty()) {
            // `key: []` loses its brackets and gets the block beneath it.
            final String keyLine = lines.get(span.keyLine());
            final String keyBody = LineEdits.withoutLineEnding(keyLine);
            final int colon = LineEdits.colonOf(keyBody, span, entry);
            lines.set(
                    span.keyLine(),
                    keyBody.substring(0, colon + 1)
                            + LineEdits.trailingComment(keyBody, span, colon)
                            + keyLine.substring(keyBody.length()));
            LineEdits.insertLinesAfter(lines, span.keyLine(), newLines);
        } else {
            // The end of this list's block, not the file's, so what follows stays after the new entry.
            LineEdits.insertLinesAfter(lines, LineEdits.sequenceExtent(lines, span.keyLine(), span.start()), newLines);
        }
    }

    /** Writes one new entry into {@code out} and answers with the logical value a re-read gives back. */
    private static Map<String, Object> renderSection(
            final List<ConfigEntry> shape,
            final Map<String, Object> values,
            final int dashColumn,
            final int fieldColumn,
            final String entryPath,
            final String inner,
            final List<String> out) {
        final Map<String, Object> row = new LinkedHashMap<>();
        boolean first = true;
        for (final ConfigEntry field : shape) {
            final Object value = values.get(field.key());
            if (value == null) {
                throw new IllegalArgumentException(
                        field.key() + " is missing from the new entry of " + entryPath + " that was sent to be saved");
            }
            final String prefix = (first ? " ".repeat(dashColumn) + "- " : " ".repeat(fieldColumn)) + field.key() + ":";
            first = false;
            final String fieldPath = entryPath + "." + field.key();
            row.put(field.key(), renderField(field, value, prefix, fieldColumn, entryPath, fieldPath, inner, out));
        }
        return Collections.unmodifiableMap(row);
    }

    private static Object renderField(
            final ConfigEntry field,
            final Object value,
            final String prefix,
            final int fieldColumn,
            final String entryPath,
            final String fieldPath,
            final String inner,
            final List<String> out) {
        return switch (field.kind()) {
            case SCALAR -> renderScalarField(field, value, prefix, entryPath, fieldPath, inner, out);
            case LIST -> renderListField(field, value, prefix, fieldColumn, fieldPath, inner, out);
            case SECTIONS -> renderSectionsField(field, value, prefix, fieldColumn, fieldPath, inner, out);
            case MAP ->
                throw new IllegalArgumentException(
                        fieldPath + " is a nested section and cannot be written into a new entry");
        };
    }

    private static String renderScalarField(
            final ConfigEntry field,
            final Object value,
            final String prefix,
            final String entryPath,
            final String fieldPath,
            final String inner,
            final List<String> out) {
        final String text = (String) shapedFor(field, value);
        if (text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    entryPath + ": a new entry cannot hold a multi-line value (" + field.key() + ")");
        }
        out.add(prefix + " " + Scalars.render(field.type(), text, fieldPath) + inner);
        return text;
    }

    private static List<String> renderListField(
            final ConfigEntry field,
            final Object value,
            final String prefix,
            final int fieldColumn,
            final String fieldPath,
            final String inner,
            final List<String> out) {
        final List<String> items = itemsOf(field, value);
        if (items.isEmpty()) {
            out.add(prefix + " []" + inner);
        } else {
            out.add(prefix + inner);
            for (final String item : items) {
                out.add(" ".repeat(fieldColumn) + "- " + Scalars.renderItem(field.type(), item, fieldPath, false)
                        + inner);
            }
        }
        return List.copyOf(items);
    }

    private static List<Map<String, Object>> renderSectionsField(
            final ConfigEntry field,
            final Object value,
            final String prefix,
            final int fieldColumn,
            final String fieldPath,
            final String inner,
            final List<String> out) {
        final List<Map<String, Object>> records = recordsOf(field, value);
        final List<ConfigEntry> nested = shapeOf(field);
        if (!records.isEmpty() && nested.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldPath + " has no entry yet to copy the shape of a new one from - add the first entry by hand");
        }
        final List<Map<String, Object>> nestedRows = new ArrayList<>();
        if (records.isEmpty()) {
            out.add(prefix + " []" + inner);
        } else {
            out.add(prefix + inner);
            for (final Map<String, Object> record : records) {
                nestedRows.add(renderSection(nested, record, fieldColumn, fieldColumn + 2, fieldPath, inner, out));
            }
        }
        return List.copyOf(nestedRows);
    }

    /**
     * Removes exactly one entry from a {@link Kind#SECTIONS} list, with its own comments and not the next one's.
     *
     * Removing the last entry leaves {@code key: []}, never a bare {@code key:} that reads back as null.
     */
    private static List<Map<String, Object>> removeSection(
            final Parsed parsed,
            final List<String> lines,
            final ConfigEntry entry,
            final Span span,
            final List<Map<String, Object>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();

        final List<Integer> fits = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            if (fitsWithout(existing, incoming, index)) {
                fits.add(index);
            }
        }
        if (fits.isEmpty()) {
            throw new IllegalArgumentException(entry.path() + " has " + existing.size() + " entries"
                    + " in the file right now, but what was sent does not read as exactly one of"
                    + " them removed and nothing else changed - remove an entry and edit a field in"
                    + " separate saves");
        }
        final int removedIndex = fits.getFirst();
        // An empty secret matches every card, so two cards differing only in a secret cannot be told apart.
        for (final int other : fits) {
            if (!sameValues(existing.get(other), existing.get(removedIndex))) {
                throw new IllegalArgumentException(entry.path() + ": two entries differ only in a secret,"
                        + " so which one was removed cannot be told - remove it in the file itself");
            }
        }

        final List<ConfigEntry> removed = existing.get(removedIndex);
        refuseIfProtected(entry, removed);
        if (existing.size() == 1) {
            ScalarWriter.sequence(lines, entry, span, List.of());
        } else {
            final int entryStartLine = spanOf(parsed, removed.getFirst()).keyLine();
            final int entryEndLine = entryExtent(lines, entryStartLine, span.start());
            final int deleteFrom = ConfigFileReader.commentBlockStartLine(lines, entryStartLine);
            lines.subList(deleteFrom, entryEndLine + 1).clear();
        }

        final List<Map<String, Object>> written = new ArrayList<>(existing.size() - 1);
        for (int index = 0; index < existing.size(); index++) {
            if (index != removedIndex) {
                written.add(rowOf(existing.get(index)));
            }
        }
        return List.copyOf(written);
    }

    /** The last line of the entry starting on {@code startLine}, excluding the comments above the next entry. */
    private static int entryExtent(final List<String> lines, final int startLine, final int dashColumn) {
        int last = startLine;
        for (int i = startLine + 1; i < lines.size(); i++) {
            final String text = LineEdits.withoutLineEnding(lines.get(i));
            if (text.isBlank() || text.strip().startsWith("#")) {
                continue;
            }
            if (LineEdits.indentOf(text) <= dashColumn) {
                break;
            }
            last = i;
        }
        return last;
    }

    /** Refuses to remove the one section {@code entry}'s schema names as protected. */
    private static void refuseIfProtected(final ConfigEntry entry, final List<ConfigEntry> removed) {
        final ConfigEntry.Protected protectedEntry = entry.protectedEntry();
        if (protectedEntry == null) {
            return;
        }
        for (final ConfigEntry field : removed) {
            if (field.key().equals(protectedEntry.field()) && field.value().equals(protectedEntry.value())) {
                // The page already shows the schema's explanation next to the list.
                throw new IllegalArgumentException(entry.path() + ": the entry whose "
                        + protectedEntry.field() + " is '" + protectedEntry.value() + "' cannot be"
                        + " removed - the schema marks it as required.");
            }
        }
    }

    /** Whether {@code incoming} is {@code existing} with the entry at {@code removed} left out. */
    private static boolean fitsWithout(
            final List<List<ConfigEntry>> existing, final List<Map<String, Object>> incoming, final int removed) {
        if (incoming.size() != existing.size() - 1) {
            return false;
        }
        for (int index = 0; index < incoming.size(); index++) {
            if (!matchesSection(existing.get(index < removed ? index : index + 1), incoming.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Whether two entries hold the same values, secrets included. */
    private static boolean sameValues(final List<ConfigEntry> left, final List<ConfigEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).key().equals(right.get(index).key())
                    || !valueOf(left.get(index)).equals(valueOf(right.get(index)))) {
                return false;
            }
        }
        return true;
    }

    /** Whether every field of {@code fields} already reads exactly as {@code candidate} says. */
    private static boolean matchesSection(
            final List<ConfigEntry> fields, final @Nullable Map<String, Object> candidate) {
        if (candidate == null) {
            return false;
        }
        for (final ConfigEntry field : fields) {
            final Object value = candidate.get(field.key());
            if (value == null || (!keepsSecret(field, value) && !value.equals(valueOf(field)))) {
                return false;
            }
        }
        return true;
    }

    /** One entry's fields as {@link #sections} answers with. */
    private static Map<String, Object> rowOf(final List<ConfigEntry> fields) {
        final Map<String, Object> row = new LinkedHashMap<>();
        for (final ConfigEntry field : fields) {
            row.put(field.key(), valueOf(field));
        }
        return Collections.unmodifiableMap(row);
    }

    /** The same shape {@link #sections} writes, read back out of a parsed {@link Kind#SECTIONS} entry. */
    static List<Map<String, Object>> sectionValuesOf(final ConfigEntry entry) {
        return entry.sections().stream().map(SectionWriter::rowOf).toList();
    }
}
