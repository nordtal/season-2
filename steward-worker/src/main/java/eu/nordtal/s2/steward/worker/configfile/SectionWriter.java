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
 * Rewrites the fields of a {@link Kind#SECTIONS} entry that actually changed.
 *
 * Every other field, and with it every comment and the key order around it, is left untouched.
 *
 * A field already on its own line is rewritten the way {@link ScalarWriter#scalar} rewrites a top-level one: only
 * the characters of that value move. A field whose sent value equals what {@code entry.sections()} already read is
 * not touched at all, which is what keeps the surrounding comments and key order of an entry nobody asked to change
 * provably still there afterwards, byte for byte.
 *
 * A field can be a list itself, of values or of sections one level down, rewritten by this same class. Adding or
 * removing more than one entry of the same list in one save is refused: {@code incoming} may name exactly as many
 * entries as {@code entry.sections()} already has (an ordinary field edit), or exactly one more or one fewer.
 * Anything else is a diff this class does not try to read: guessing which of several changed entries was added,
 * removed or edited is how a config editor becomes untrustworthy.
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
        // Bottom of the file upwards: a field rewritten as a block shifts every span below it otherwise.
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

    /**
     * A sent value in the shape {@code field} holds, or a refusal naming both.
     *
     * A {@link String} for a scalar, a list of strings for a list, a list of records for a list of sections.
     */
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

    /**
     * The field set a new entry of {@code entry} is written with.
     *
     * The last existing entry's own, so the file keeps its order, or the schema's template for a list with nothing
     * in it yet. Empty when neither exists.
     */
    private static List<ConfigEntry> shapeOf(final ConfigEntry entry) {
        if (!entry.sections().isEmpty()) {
            return entry.sections().getLast();
        }
        return entry.template();
    }

    /**
     * Appends one new entry to a {@link Kind#SECTIONS} list.
     *
     * Only a pure append is accepted: {@code incoming} has to carry every existing entry completely unchanged, in
     * order, plus exactly one more entry at the end.
     *
     * The new entry's shape is copied from the existing last entry, never invented: the same field keys, in the
     * same order, at the same two columns, preceded by a blank line only if the last entry already was. The last
     * entry, rather than the first, is the safe one to copy - the first is the one entry that might carry a comment
     * the new one must not inherit.
     *
     * A list with no entries at all has no shape to copy. When the schema describes one, the template is used and
     * the entry is written the way jcore writes a list, {@code - } at the key's own column; {@code objectives: []}
     * becomes {@code objectives:} with the block under it. Without a schema this refuses rather than inventing a
     * shape.
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
                if (!shapedFor(field, wantedValue).equals(valueOf(field))) {
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
            // The end of THIS list's own block, not of the file, so what follows still ends up after the new entry.
            LineEdits.insertLinesAfter(lines, LineEdits.sequenceExtent(lines, span.keyLine(), span.start()), newLines);
        }
    }

    /**
     * Writes one new entry into {@code out}, and answers with what it reads back as.
     *
     * {@code - } sits at {@code dashColumn} and every further field at {@code fieldColumn}. The written text is
     * quoted where the value needs it, while the answer holds the logical value a re-read of that line comes back
     * as, which is what the save's own verification compares against.
     */
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
     * Removes exactly one entry from a {@link Kind#SECTIONS} list.
     *
     * Only a pure removal is accepted, for the same reason {@link #appendSection} only accepts a pure append. The
     * comment lines that belong to the removed entry go with it, and none that belong to the next one: a comment
     * directly above the next entry's {@code - } line belongs to that one, and the extent stops before it.
     * Everything nested inside the entry goes with it. Removing the last entry of a list leaves {@code key: []},
     * never a bare {@code key:} that would read back as null.
     */
    private static List<Map<String, Object>> removeSection(
            final Parsed parsed,
            final List<String> lines,
            final ConfigEntry entry,
            final Span span,
            final List<Map<String, Object>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();

        int removedIndex = -1;
        for (int index = 0; index < existing.size(); index++) {
            final Map<String, Object> candidate = index < incoming.size() ? incoming.get(index) : null;
            if (!matchesSection(existing.get(index), candidate)) {
                removedIndex = index;
                break;
            }
        }
        if (removedIndex < 0) {
            // Every named entry matched exactly; the missing one is the extra entry at the end of `existing`.
            removedIndex = existing.size() - 1;
        }
        boolean ok = true;
        for (int index = removedIndex; index < incoming.size(); index++) {
            if (!matchesSection(existing.get(index + 1), incoming.get(index))) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            throw new IllegalArgumentException(entry.path() + " has " + existing.size() + " entries"
                    + " in the file right now, but what was sent does not read as exactly one of"
                    + " them removed and nothing else changed - remove an entry and edit a field in"
                    + " separate saves");
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

    /**
     * The last line of the entry whose {@code - } sits on {@code startLine} at {@code dashColumn}.
     *
     * Everything indented past the dash, down to the next line that is not - but not the comment lines directly
     * above that next line, which belong to whatever follows.
     */
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

    /**
     * Refuses to remove the one section {@code entry}'s schema names as protected.
     *
     * Nothing here assumes which list this is or what the protected value means: it only reads
     * {@code entry.protectedEntry()} and one field of {@code removed}, the same way every other rule in this class
     * reads the schema rather than hardcoding a list's name.
     */
    private static void refuseIfProtected(final ConfigEntry entry, final List<ConfigEntry> removed) {
        final ConfigEntry.Protected protectedEntry = entry.protectedEntry();
        if (protectedEntry == null) {
            return;
        }
        for (final ConfigEntry field : removed) {
            if (field.key().equals(protectedEntry.field()) && field.value().equals(protectedEntry.value())) {
                // An error says what happened; the page already shows the schema's explanation next to the list.
                throw new IllegalArgumentException(entry.path() + ": the entry whose "
                        + protectedEntry.field() + " is '" + protectedEntry.value() + "' cannot be"
                        + " removed - the schema marks it as required.");
            }
        }
    }

    /** Whether every field of {@code fields} already reads exactly as {@code candidate} says. */
    private static boolean matchesSection(
            final List<ConfigEntry> fields, final @Nullable Map<String, Object> candidate) {
        if (candidate == null) {
            return false;
        }
        for (final ConfigEntry field : fields) {
            final Object value = candidate.get(field.key());
            if (value == null || !value.equals(valueOf(field))) {
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

    /**
     * The same shape {@link #sections} writes, read back out of an already-parsed {@link Kind#SECTIONS} entry.
     */
    static List<Map<String, Object>> sectionValuesOf(final ConfigEntry entry) {
        return entry.sections().stream().map(SectionWriter::rowOf).toList();
    }
}
