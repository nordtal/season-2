package eu.nordtal.season.messages.document;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the code fills into a document: a button, a select, a data list, a block per language or a field.
 * A required slot is placed exactly once, an optional one at most once.
 *
 * @param values what an item of a data list or an option of a select fills its line with
 */
public record Slot(String id, Type type, boolean required, Map<String, Value> values) {

    public enum Type {
        BUTTON,
        /** A select of options or of members. */
        SELECT,
        /** One line per item. */
        LIST,
        /** Repeats its parts once per configured language, each in that language. */
        LANGUAGES,
        /** A field of a modal: a text input or a select under its label. */
        INPUT
    }

    public Slot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        values = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNullElse(values, Map.of())));
    }

    public static Slot button(final String id) {
        return new Slot(id, Type.BUTTON, true, Map.of());
    }

    /** A select whose options the code labels, or a member select. */
    public static Slot select(final String id) {
        return new Slot(id, Type.SELECT, true, Map.of());
    }

    /** A select whose document draws each option with its option line. */
    public static Slot select(final String id, final Class<? extends Record> option) {
        return new Slot(id, Type.SELECT, true, Value.of(option));
    }

    public static Slot list(final String id, final Class<? extends Record> item) {
        return new Slot(id, Type.LIST, true, Value.of(item));
    }

    public static Slot languages(final String id) {
        return new Slot(id, Type.LANGUAGES, true, Map.of());
    }

    public static Slot input(final String id) {
        return new Slot(id, Type.INPUT, true, Map.of());
    }

    public Slot optional() {
        return new Slot(id, type, false, values);
    }

    /** Returns whether the select draws its options with a line of the document. */
    public boolean lined() {
        return !values.isEmpty();
    }
}
