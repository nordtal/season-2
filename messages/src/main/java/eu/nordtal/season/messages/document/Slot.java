package eu.nordtal.season.messages.document;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the code fills into a document: a button, a select, a data list, a block per language, a field or a menu part.
 * A required slot is placed exactly once, an optional one at most once; a data list may be drawn in several places.
 *
 * @param values what an item of a data list, an option of a select or each entry of a menu's item or label fills its
 *     lines with
 * @param at the chest slots a menu's item is placed in, numbered row by row from the top left; empty for every other
 *     slot
 */
public record Slot(String id, Type type, boolean required, Map<String, Value> values, List<Integer> at) {

    public enum Type {
        BUTTON,
        /** A select of options or of members. */
        SELECT,
        /** One line per item. */
        LIST,
        /** Repeats its parts once per configured language, each in that language. */
        LANGUAGES,
        /** A field of a modal: a text input or a select under its label. */
        INPUT,
        /** A menu's item, its name and lore. */
        ITEM,
        /** A word painted into a menu's window. */
        LABEL
    }

    public Slot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        values = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNullElse(values, Map.of())));
        at = List.copyOf(Objects.requireNonNullElse(at, List.of()));
    }

    public static Slot button(final String id) {
        return new Slot(id, Type.BUTTON, true, Map.of(), List.of());
    }

    /** A select whose options the code labels, or a member select. */
    public static Slot select(final String id) {
        return new Slot(id, Type.SELECT, true, Map.of(), List.of());
    }

    /** A select whose document draws each option with its option line. */
    public static Slot select(final String id, final Class<? extends Record> option) {
        return new Slot(id, Type.SELECT, true, Value.of(option), List.of());
    }

    public static Slot list(final String id, final Class<? extends Record> item) {
        return new Slot(id, Type.LIST, true, Value.of(item), List.of());
    }

    public static Slot languages(final String id) {
        return new Slot(id, Type.LANGUAGES, true, Map.of(), List.of());
    }

    public static Slot input(final String id) {
        return new Slot(id, Type.INPUT, true, Map.of(), List.of());
    }

    public static Slot item(final String id) {
        return new Slot(id, Type.ITEM, true, Map.of(), List.of());
    }

    /** An item drawn once per entry, each filled with the entry's values. */
    public static Slot item(final String id, final Class<? extends Record> entry) {
        return new Slot(id, Type.ITEM, true, Value.of(entry), List.of());
    }

    public static Slot label(final String id) {
        return new Slot(id, Type.LABEL, true, Map.of(), List.of());
    }

    /** A label painted once per entry, each filled with the entry's values. */
    public static Slot label(final String id, final Class<? extends Record> entry) {
        return new Slot(id, Type.LABEL, true, Value.of(entry), List.of());
    }

    /** Returns this slot as one a document may leave out. A preview draws an optional data list without items. */
    public Slot optional() {
        return new Slot(id, type, false, values, at);
    }

    /** Returns this menu item placed in {@code slots}. */
    public Slot at(final List<Integer> slots) {
        return new Slot(id, type, required, values, slots);
    }

    /** Returns whether the select draws its options with a line of the document. */
    public boolean lined() {
        return !values.isEmpty();
    }
}
