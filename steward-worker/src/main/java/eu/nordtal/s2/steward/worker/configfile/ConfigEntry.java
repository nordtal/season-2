package eu.nordtal.s2.steward.worker.configfile;

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
 *                 {@code token: ''} arrives here as the empty string, and a block scalar
 *                 ({@code motd: |-}) arrives with the newlines it holds. Empty for a
 *                 {@link Kind#LIST} or a {@link Kind#MAP}, which have no scalar of their own
 * @param items    the entries of a {@link Kind#LIST}, in file order, each unquoted the same way
 *                 {@link #value()} is. Empty for every other kind - and an empty list is also
 *                 empty here, which is the same thing a form needs to draw either way
 * @param kind     what sits under the key: a scalar, a sequence or a nested mapping
 * @param type     for a SCALAR, what it looks like to YAML - see {@link Type}. <b>For a LIST, the
 *                 type its entries share</b>, or {@link Type#STRING} when they are mixed or there
 *                 are none; that is what decides how a new entry is written back, so that a list
 *                 of ports stays a list of numbers instead of quietly becoming strings. Always
 *                 {@link Type#STRING} for a MAP, which has no value of its own
 * @param line     the 1-based line the key sits on, for an error message that can be acted on
 * @param editable whether {@link ConfigFiles#write} will accept a change to this key: true for any
 *                 scalar, single-line or block, and for a list whose entries are all scalars.
 *                 False for a nested section, which has no value to change, and for a list of
 *                 sections - rewriting one of those would move comments and keys around, and a
 *                 config editor that reformats a file nobody asked it to touch is one nobody will
 *                 trust twice
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
        @NotNull List<String> items,
        @NotNull Kind kind,
        @NotNull Type type,
        int line,
        boolean editable,
        boolean secret) {

    /** What sits under a key. */
    public enum Kind {
        /** A single value, on one line or written as a block ({@code |}, {@code >}). */
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

    /** Defensive copies, so a document cannot be edited through an entry. */
    public ConfigEntry {
        comments = List.copyOf(comments);
        items = List.copyOf(items);
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
