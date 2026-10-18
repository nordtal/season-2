package eu.nordtal.season.messages.spec.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** An element is placed once in the tree by its id.
 * An editor may move the code's elements and never deletes them.
 *
 * @param keys the texts the code draws in it as {@code bundle/key}: a button's label, a select's
 *     placeholder and then its option line if it has one, a data list's line, a modal field's label
 *     and then its placeholder if it has one. A block per language and a link button have none.
 */
public record Element(
        String id,
        Kind kind,
        List<String> keys,
        @Nullable String url,
        @Nullable Map<String, String> labels) {

    public enum Kind {
        BUTTON,
        SELECT,
        /** One line per item, filled with the item's values. */
        LIST,
        /** Repeats its parts once per configured language, each in that language. */
        LANGUAGES,
        /** A field of a modal: a text input or a select under its label. */
        INPUT,
        /** A button that an editor added and that opens an address. */
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

    /** Each option is drawn by the line in {@code option}, if given. */
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

    /** The field shows {@code placeholder} while empty, if given. */
    public static Element input(final String id, final String label, final String... placeholder) {
        final List<String> keys = new ArrayList<>(List.of(label));
        keys.addAll(List.of(placeholder));
        return new Element(id, Kind.INPUT, keys);
    }

    public static Element link(final String id, final String url, final Map<String, String> labels) {
        return new Element(id, Kind.LINK, List.of(), url, labels);
    }
}
