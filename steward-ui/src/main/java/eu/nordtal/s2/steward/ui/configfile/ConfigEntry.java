package eu.nordtal.s2.steward.ui.configfile;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One key of a jcore-written config file, as the interface needs to draw it.
 *
 * <p>Everything here comes from the <em>file</em>, never from a {@code @ConfigSpec} class. That is
 * the whole point of this package: steward-ui shows the worker's {@code steward.yml} and four Paper
 * plugins' {@code config.yml} without having a single one of those modules on its classpath. jcore
 * writes its {@code @Comment}s into the YAML, so a file it wrote carries its own documentation.</p>
 *
 * @param path     the dotted path from the document root, e.g. {@code worker.base-url}
 * @param key      the leaf key on its own, e.g. {@code base-url}
 * @param label    the leaf key as a human reads it, e.g. {@code Base url} - see
 *                 {@link Labels#of(String)}; the {@link #path()} is carried alongside so the
 *                 interface can still show the machine-readable name in monospace
 * @param comments the block of {@code #} comment directly above the key, in order, with the
 *                 leading {@code # } removed. Empty when there is none
 * @param value    the scalar as it stands in the file, unquoted and unescaped - so a
 *                 {@code token: ''} arrives here as the empty string. Empty for a
 *                 {@link Kind#LIST} or a {@link Kind#MAP}, which have no scalar of their own
 * @param kind     what sits under the key: a scalar, a sequence or a nested mapping
 * @param type     what the scalar looks like to YAML - see {@link Type}. Always
 *                 {@link Type#STRING} for a LIST or a MAP, which is meaningless there and is
 *                 never used, because neither is editable
 * @param line     the 1-based line the key sits on, for an error message that can be acted on
 * @param editable whether {@link ConfigFiles#write} will accept a change to this key: true for a
 *                 scalar that occupies a single line, false for a list, a nested section and a
 *                 block scalar spanning several lines
 * @param secret   whether the leaf key names a credential - see {@link #isSecretKey(String)}. The
 *                 value is still carried: hiding it here would mean the form could not round-trip
 *                 it, and the browser would blank a token by saving a page. It is the interface's
 *                 job to render it as a password field and to keep it out of every log line
 */
public record ConfigEntry(
        @NotNull String path,
        @NotNull String key,
        @NotNull String label,
        @NotNull List<String> comments,
        @NotNull String value,
        @NotNull Kind kind,
        @NotNull Type type,
        int line,
        boolean editable,
        boolean secret) {

    /** What sits under a key. */
    public enum Kind {
        /** A single value. The only kind this alpha can write. */
        SCALAR,
        /** A YAML sequence, block ({@code - item}) or flow ({@code []}). */
        LIST,
        /** A nested mapping. It exists so the interface can group the keys beneath it. */
        MAP
    }

    /** What a scalar looks like to YAML, which is what decides the form control and the check on save. */
    public enum Type {
        /** Anything that is not one of the three below, including an empty and an absent value. */
        STRING,
        /** A whole number. */
        INTEGER,
        /** A number with a fractional part or an exponent. */
        DECIMAL,
        /** {@code true} or {@code false}. */
        BOOLEAN
    }

    /** Defensive copy of the comment block, so a document cannot be edited through an entry. */
    public ConfigEntry {
        comments = List.copyOf(comments);
    }

    /**
     * Whether a leaf key names something that must not be shown or logged in the clear.
     *
     * <p>It is a substring test on purpose and it over-matches - {@code public-key} is not a
     * secret and is caught anyway. That is the right direction to be wrong in: a value wrongly
     * hidden behind a password field costs a click, a value wrongly printed costs a rotation.</p>
     *
     * @param key the leaf key
     * @return whether the key contains {@code secret}, {@code token}, {@code password} or {@code key}
     */
    public static boolean isSecretKey(final @NotNull String key) {
        final String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("secret")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("key");
    }
}
