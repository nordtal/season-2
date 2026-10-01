package eu.nordtal.s2.steward.configfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a form is asking a key to say, as a scalar, a list or sections.
 *
 * {@link ConfigFiles#write} refuses a change whose shape differs from the key's, naming both.
 */
public sealed interface ConfigChange {

    /**
     * Returns a scalar change.
     *
     * @param text the new scalar; newlines in it make the key a block scalar
     */
    static ConfigChange of(final String text) {
        return new Text(text);
    }

    /**
     * Returns a list change.
     *
     * @param items the new entries of a sequence, in order; empty writes an empty list
     */
    static ConfigChange list(final List<String> items) {
        return new Items(items);
    }

    /**
     * Returns a sections change.
     *
     * @param sections one {@code field key -> new value} record per entry, in order; see {@link Sections}
     */
    static ConfigChange sections(final List<? extends Map<String, ?>> sections) {
        return new Sections(
                sections.stream().<Map<String, Object>>map(LinkedHashMap::new).toList());
    }

    /**
     * A new value for a {@link ConfigEntry.Kind#SCALAR}.
     *
     * Newlines write a literal block, falling back to a double-quoted line when a block cannot hold it.
     */
    record Text(String text) implements ConfigChange {}

    /**
     * New entries for a {@link ConfigEntry.Kind#LIST}, replacing the list whole in the type it already holds.
     *
     * A hand-written comment between two entries is lost on rewrite.
     */
    record Items(List<String> items) implements ConfigChange {

        public Items {
            items = List.copyOf(items);
        }
    }

    /**
     * New field values for every entry of a {@link ConfigEntry.Kind#SECTIONS} list, one record per entry, in order.
     *
     * The count may differ from the file's by one, as a pure append or removal; a field the entry lacks is ignored.
     */
    record Sections(List<Map<String, Object>> sections) implements ConfigChange {

        public Sections {
            sections = List.copyOf(sections);
        }
    }
}
