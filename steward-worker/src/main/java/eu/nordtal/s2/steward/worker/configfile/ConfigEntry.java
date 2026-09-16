package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * One key of a jcore-written config file, as the interface needs to draw it.
 *
 * <p>Everything here comes from the <em>file</em>, and now also from its schema, but never from a
 * {@code @ConfigSpec} class directly. That is the whole point of this package: steward-ui shows the
 * worker's {@code steward.yml} and four Paper plugins' {@code config.yml} without having a single
 * one of those modules on its classpath. Since jcore 4.0.0 the YAML itself carries no comments any
 * more - a file it wrote documents itself through the {@code <name>.schema.json} beside it instead
 * (steward/54, steward/55). {@link #comments()} is what is left of the old self-documentation, for
 * a file with no schema, or one written by something other than this generation of jcore.</p>
 *
 * @param path                the dotted path from the document root, e.g. {@code worker.base-url}
 * @param key                 the leaf key on its own, e.g. {@code base-url}
 * @param label               the leaf key as a human reads it, e.g. {@code Base url}. From the
 *                            schema when one covers this key, mechanically from the key itself
 *                            otherwise - see {@link Labels#of(String)}. The {@link #path()} is
 *                            carried alongside so the interface can still show the machine-readable
 *                            name in monospace
 * @param comments            the block of {@code #} comment directly above the key, in order, with
 *                            the leading {@code # } removed. Empty when there is none - which is
 *                            every key of every file a current jcore wrote, schema or not
 * @param explanation         the short text from the schema's {@code @Explain}, or the empty string
 *                            when there is no schema entry for this key. Never as long as a
 *                            Javadoc-style {@code @Comment}; that text intentionally never reaches
 *                            this class
 * @param noExplanationNeeded whether the schema explicitly says this setting needs no explanation
 *                            at all - which the interface has to draw as no text, not as an empty
 *                            one. Always {@code false} when there is no schema entry for this key
 * @param value               the scalar as it stands in the file, unquoted and unescaped - so a
 *                            {@code token: ''} arrives here as the empty string, and a block scalar
 *                            ({@code motd: |-}) arrives with the newlines it holds. Empty for a
 *                            {@link Kind#LIST} or a {@link Kind#MAP}, which have no scalar of their
 *                            own
 * @param items               the entries of a {@link Kind#LIST}, in file order, each unquoted the
 *                            same way {@link #value()} is. Empty for every other kind - and an
 *                            empty list is also empty here, which is the same thing a form needs to
 *                            draw either way
 * @param kind                what sits under the key: a scalar, a sequence or a nested mapping.
 *                            Always read from the file, schema or not - the file is the truth
 *                            (steward/50)
 * @param type                for a SCALAR, what it looks like to YAML - see {@link Type}. <b>For a
 *                            LIST, the type its entries share</b>, or {@link Type#STRING} when they
 *                            are mixed or there are none; that is what decides how a new entry is
 *                            written back, so that a list of ports stays a list of numbers instead
 *                            of quietly becoming strings. Always {@link Type#STRING} for a MAP,
 *                            which has no value of its own. Read from the file, never the schema
 * @param line                the 1-based line the key sits on, for an error message that can be
 *                            acted on
 * @param editable            whether {@link ConfigFiles#write} will accept a change to this key:
 *                            true for any scalar, single-line or block, and for a list whose entries
 *                            are all scalars. False for a nested section, which has no value to
 *                            change, and for a list of sections - rewriting one of those would move
 *                            comments and keys around, and a config editor that reformats a file
 *                            nobody asked it to touch is one nobody will trust twice
 * @param secret              whether this key holds a credential. {@code true} the moment either
 *                            says so: the schema's {@code @Secret}, or the leaf-key heuristic (see
 *                            {@link #isSecretKey(String)}). The heuristic is a net that stays under
 *                            the schema rather than being replaced by it (steward/50) - a schema
 *                            that has not yet been annotated {@code @Secret} must never turn off a
 *                            protection the key's own name already earned. The value is still
 *                            carried: hiding it here would mean the form could not round-trip it,
 *                            and the browser would blank a token by saving a page. It is the
 *                            interface's job to render it as a password field and to keep it out of
 *                            every log line
 * @param inSchema            whether this key is one the schema declares - always {@code true} when
 *                            the file has no schema at all, since there is then nothing to be
 *                            missing from. {@code false} only when a schema exists for this file (or
 *                            this section of it) and does not mention this key: a setting the
 *                            software has retired, or one typed by hand. Never hidden for it
 *                            (steward/50, steward/55)
 * @param choices             the allowed (or suggested) values from the schema, and whether the
 *                            list is closed to them, or {@code null} when there is no schema entry
 *                            for this key or it names none
 */
public record ConfigEntry(
        @NotNull String path,
        @NotNull String key,
        @NotNull String label,
        @NotNull List<String> comments,
        @NotNull String explanation,
        boolean noExplanationNeeded,
        @NotNull String value,
        @NotNull List<String> items,
        @NotNull Kind kind,
        @NotNull Type type,
        int line,
        boolean editable,
        boolean secret,
        boolean inSchema,
        @Nullable Choices choices) {

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

    /**
     * The allowed (or suggested) values of a setting, from its schema.
     *
     * @param values the values, in the order the schema gives them
     * @param strict {@code true} for a closed list with no free text; {@code false} for a select
     *               with a free-text field beside it - see
     *               {@code eu.nordtal.jcore.config.spec.annotation.AllowedValues#strict()}
     */
    public record Choices(@NotNull @Unmodifiable List<String> values, boolean strict) {

        public Choices {
            values = List.copyOf(values);
        }
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
