package eu.nordtal.season.messages.spec.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Something a layout's code fills in, placed once in the tree by its id; an editor may move it but never delete it.
 *
 * @param id   how the tree places it, unique in its layout
 * @param kind what it is
 * @param keys the texts the code draws in it as {@code bundle/key}: a button's label, a select's placeholder and then
 *             the line of its options if they have one, a data list's line; none for a block per language, whose
 *             texts are in the tree
 */
public record Element(String id, Kind kind, List<String> keys) {

    /** What the code fills in. */
    public enum Kind {
        BUTTON,
        SELECT,
        /** A data list: one line per item, each the list's line filled with that item's values. */
        LIST,
        /** The parts it holds, once per configured language, each in that language. */
        LANGUAGES
    }

    public Element {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        keys = List.copyOf(keys);
    }

    public static Element button(final String id, final String label) {
        return new Element(id, Kind.BUTTON, List.of(label));
    }

    /** A select whose options the code fills in, each drawn by {@code option} when it is given. */
    public static Element select(final String id, final String placeholder, final String... option) {
        final List<String> keys = new ArrayList<>(List.of(placeholder));
        keys.addAll(List.of(option));
        return new Element(id, Kind.SELECT, keys);
    }

    public static Element list(final String id, final String line) {
        return new Element(id, Kind.LIST, List.of(line));
    }

    public static Element languages(final String id) {
        return new Element(id, Kind.LANGUAGES, List.of());
    }
}
