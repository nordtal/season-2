package eu.nordtal.s2.steward.worker.configfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a form is asking a key to say.
 *
 * <p>There are two shapes rather than one string, and the difference is not cosmetic: a list of one
 * entry and a scalar are indistinguishable once both are a {@code String}, and guessing between
 * them is how {@code stop-services: smp} gets written over a sequence - which is a config that
 * parses, starts nothing, and looks right in a diff. {@link ConfigFiles#write} refuses a change
 * whose shape does not match the shape the key already has, and the refusal names both.</p>
 */
public sealed interface ConfigChange {

    /** @param text the new scalar; newlines in it make the key a block scalar */
    static ConfigChange of(final String text) {
        return new Text(text);
    }

    /** @param items the new entries of a sequence, in order; empty writes an empty list */
    static ConfigChange list(final List<String> items) {
        return new Items(items);
    }

    /**
     * @param sections one {@code field key -> new value} record per entry of a
     *                 {@link ConfigEntry.Kind#SECTIONS} list, in order - see {@link Sections}
     */
    static ConfigChange sections(final List<? extends Map<String, ?>> sections) {
        return new Sections(
                sections.stream().<Map<String, Object>>map(LinkedHashMap::new).toList());
    }

    /**
     * A new value for a {@link ConfigEntry.Kind#SCALAR}.
     *
     * <p>A {@code text} holding newlines is written as a literal block ({@code |-}, {@code |} or
     * {@code |+} depending on how it ends), so a greeting typed into a textarea comes back out of
     * the file as the same greeting. If the value cannot be written that way - a first line that
     * begins with a space, say - it falls back to a double-quoted single line, which is uglier to
     * read and still exactly right.</p>
     */
    record Text(String text) implements ConfigChange {}

    /**
     * New entries for a {@link ConfigEntry.Kind#LIST}.
     *
     * <p>The list is replaced whole - there is no "add one entry" here, because a form that sends
     * the whole list cannot lose a concurrent edit it never saw. Each entry is written in the type
     * the list already holds ({@link ConfigEntry#type()}), so a list of ports stays numeric.</p>
     *
     * <p><b>What this loses:</b> a comment sitting between two entries. jcore never writes one -
     * its {@code @Comment}s go above the key - but a hand-edited file may have one, and a rewrite
     * of the block does not carry it across.</p>
     */
    record Items(List<String> items) implements ConfigChange {

        public Items {
            items = List.copyOf(items);
        }
    }

    /**
     * New field values for every entry of a {@link ConfigEntry.Kind#SECTIONS} list the caller wants
     * on file afterwards, one {@code {key: value}} record per entry, in order.
     *
     * <p>A value has the shape of the field it goes to: a {@link String} for a scalar, a
     * {@link List} of {@link String} for a list of values, and a {@link List} of records like these
     * for a field that is itself a list of sections - an objective inside a milestone. Every level
     * follows the rules below on its own.</p>
     *
     * <p><b>The count usually matches what the file already has</b> - an ordinary edit of one or
     * more fields, on entries otherwise untouched. It may also be exactly one more (an append,
     * steward/71) or exactly one fewer (a removal, steward/71), each accepted only as a <em>pure</em>
     * add or remove: every entry {@link ConfigFiles#write} can still recognise as unchanged has to
     * come through byte-for-byte identical, or the save is refused rather than guessed at - see
     * {@code ConfigFiles.appendSection} and {@code ConfigFiles.removeSection}. Any other count, or
     * a genuine add-and-edit or remove-and-edit in the same save, is refused with a message naming
     * both counts.</p>
     *
     * <p>A field the file's own entry does not have (typically because there is no schema, or the
     * schema describes a field this particular entry never had) is silently ignored rather than
     * invented as a new line - the same "the file is the truth" rule steward/50 applies everywhere
     * else.</p>
     */
    record Sections(List<Map<String, Object>> sections) implements ConfigChange {

        public Sections {
            sections = List.copyOf(sections);
        }
    }
}
