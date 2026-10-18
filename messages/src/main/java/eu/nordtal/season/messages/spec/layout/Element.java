package eu.nordtal.season.messages.spec.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Something placed once in the tree by its id: what the code fills in, or a link button an editor added.
 * An editor may move the code's elements but never delete them.
 *
 * @param id     how the tree places it, unique in its layout
 * @param kind   what it is
 * @param keys   the texts the code draws in it as {@code bundle/key}: a button's label, a select's placeholder and
 *               then the line of its options if they have one, a data list's line, a modal field's label and then
 *               its placeholder if it has one; none for a block per language, whose texts are in the tree, and none
 *               for a link button
 * @param url    the https address a link button opens, else {@code null}
 * @param labels a link button's label by language tag, else {@code null}
 */
public record Element(
        String id,
        Kind kind,
        List<String> keys,
        @Nullable String url,
        @Nullable Map<String, String> labels) {

    /** What the code fills in. */
    public enum Kind {
        BUTTON,
        SELECT,
        /** A data list: one line per item, each the list's line filled with that item's values. */
        LIST,
        /** The parts it holds, once per configured language, each in that language. */
        LANGUAGES,
        /** A field of a modal, a text input or a select, under its label. */
        INPUT,
        /** A button an editor added that opens an address, labelled per language. */
        LINK
    }

    public Element {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        keys = List.copyOf(keys);
        labels = labels == null ? null : Collections.unmodifiableMap(new TreeMap<>(labels));
    }

    public Element(final String id, final Kind kind, final List<String> keys) {
        this(id, kind, keys, null, null);
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

    /** A modal's field, labelled by {@code label} and showing {@code placeholder} while empty when it is given. */
    public static Element input(final String id, final String label, final String... placeholder) {
        final List<String> keys = new ArrayList<>(List.of(label));
        keys.addAll(List.of(placeholder));
        return new Element(id, Kind.INPUT, keys);
    }

    public static Element link(final String id, final String url, final Map<String, String> labels) {
        return new Element(id, Kind.LINK, List.of(), url, labels);
    }
}
