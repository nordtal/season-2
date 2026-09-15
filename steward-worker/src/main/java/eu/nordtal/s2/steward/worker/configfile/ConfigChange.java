package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;

import java.util.List;

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
    static @NotNull ConfigChange of(final @NotNull String text) {
        return new Text(text);
    }

    /** @param items the new entries of a sequence, in order; empty writes an empty list */
    static @NotNull ConfigChange list(final @NotNull List<String> items) {
        return new Items(items);
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
    record Text(@NotNull String text) implements ConfigChange {
    }

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
    record Items(@NotNull List<String> items) implements ConfigChange {

        public Items {
            items = List.copyOf(items);
        }
    }
}
