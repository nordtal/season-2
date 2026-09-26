package eu.nordtal.s2.steward.worker.configfile;

import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * @param template            for a {@link Kind#SECTIONS} entry whose schema describes one uniform
 *                            shape (every field of the element a plain scalar): the field set every
 *                            existing entry is drawn with, in schema order, each carrying an empty
 *                            {@link #value()} - the blank card an "Add" starts from. Empty when there
 *                            is no schema for this list, the schema does not cover it, or an element
 *                            of it is itself a map or another list (steward/68 stops there rather
 *                            than guessing at a shape nobody described). Empty for every other kind
 * @param sections            for a {@link Kind#SECTIONS} entry: one list of field entries per
 *                            existing entry of the sequence, in file order, each field shaped the
 *                            way a top-level key is - so a section's own field can be secret, have
 *                            choices, or a schema explanation, exactly like any other key. Empty for
 *                            an empty sequence and for every other kind. These field entries are
 *                            never also flattened into the document's own top-level list of
 *                            entries - they are reached only through here
 * @param kind                what sits under the key: a scalar, a sequence of scalars, a sequence of
 *                            uniform sections, or a nested mapping. Always read from the file,
 *                            schema or not - the file is the truth (steward/50)
 * @param type                for a SCALAR, what it looks like to YAML - see {@link Type}. <b>For a
 *                            LIST, the type its entries share</b>, or {@link Type#STRING} when they
 *                            are mixed or there are none; that is what decides how a new entry is
 *                            written back, so that a list of ports stays a list of numbers instead
 *                            of quietly becoming strings. Always {@link Type#STRING} for a MAP or a
 *                            SECTIONS entry, neither of which has a value of its own. Read from the
 *                            file, never the schema
 * @param line                the 1-based line the key sits on, for an error message that can be
 *                            acted on
 * @param editable            whether {@link ConfigFiles#write} will accept a change to this key:
 *                            true for any scalar, single-line or block; for a list whose entries are
 *                            all scalars; and for a {@link Kind#SECTIONS} entry, whose fields can be
 *                            changed one value at a time (steward/68), one entry appended or one
 *                            entry removed (steward/71) - which of those a given save is doing, and
 *                            whether it is allowed to (a mixed add-and-edit is not), is decided by
 *                            {@link ConfigFiles} itself rather than this flag turning false for it.
 *                            False for a nested section, which has no value to change, and for a
 *                            sequence that mixes scalars and mappings, which is not a shape anything
 *                            here knows how to write back
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
 * @param environmentOverridden whether an environment variable has taken this exact path over, so
 *                            that a save here changes the file and never the running service until
 *                            the variable is removed (steward/76). The field stays
 *                            {@link #editable()} regardless - Till's decision is to let the file be
 *                            prepared for the day the variable goes, not to lock it. {@code null}
 *                            when the service behind this file never reported which paths the
 *                            environment overlays - older than steward/76, or not reloaded since -
 *                            which is a different fact from {@code false} and must stay a different
 *                            value: see {@code eu.nordtal.s2.common.config.EnvOverrideFile}'s own
 *                            doc for why "nobody said" is never allowed to collapse into "not
 *                            overridden"
 * @param choices             the allowed (or suggested) values from the schema, and whether the
 *                            list is closed to them, or {@code null} when there is no schema entry
 *                            for this key or it names none
 * @param protectedEntry      for a {@link Kind#SECTIONS} entry whose schema carries
 *                            {@code @Protected} (steward/74): the field and value that identify the
 *                            one section a save must never be allowed to remove - {@code tag} /
 *                            {@code en} for {@code languages}. {@code null} whenever there is no
 *                            schema for this list or its property carries no {@code @Protected}.
 *                            {@link ConfigFiles#write} is what turns this into a refusal; carrying
 *                            it here is also what lets the interface grey out the one card it names
 *                            instead of only failing after a confirmed removal is sent
 */
public record ConfigEntry(
        String path,
        String key,
        String label,
        List<String> comments,
        String explanation,
        boolean noExplanationNeeded,
        String value,
        List<String> items,
        List<ConfigEntry> template,
        List<List<ConfigEntry>> sections,
        Kind kind,
        Type type,
        int line,
        boolean editable,
        boolean secret,
        boolean inSchema,
        @Nullable Boolean environmentOverridden,
        @Nullable Choices choices,
        @Nullable Protected protectedEntry) {

    /** What sits under a key. */
    public enum Kind {
        /** A single value, on one line or written as a block ({@code |}, {@code >}). */
        SCALAR,
        /** A YAML sequence, block ({@code - item}) or flow ({@code []}), of scalars only. */
        LIST,
        /** A nested mapping. It exists so the interface can group the keys beneath it. */
        MAP,
        /**
         * A YAML sequence whose every entry is itself a mapping - {@code languages} and
         * {@code tiers} in the bot's {@code access.yml} are the cases this was built for
         * (steward/68). Drawn as one card per entry rather than as raw text.
         */
        SECTIONS
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
    public record Choices(List<String> values, boolean strict) {

        public Choices {
            values = List.copyOf(values);
        }
    }

    /**
     * Identifies the one section of a {@link Kind#SECTIONS} list that a save must refuse to remove -
     * mirrors {@code eu.nordtal.jcore.config.schema.SchemaNode.ProtectedEntry}, which is where it
     * comes from.
     *
     * @param field the field key within one section to match against, e.g. {@code tag}
     * @param value the value that field must equal for that section to be the protected one
     */
    public record Protected(String field, String value) {}

    /** Defensive copies, so a document cannot be edited through an entry. */
    public ConfigEntry {
        comments = List.copyOf(comments);
        items = List.copyOf(items);
        template = List.copyOf(template);
        sections = sections.stream().map(List::copyOf).toList();
    }

    /**
     * Whether a leaf key names something that must not be shown or logged in the clear.
     *
     * <p>It is a substring test on purpose and it over-matches - {@code public-key} is not a
     * secret and is caught anyway. That is the right direction to be wrong in: a value wrongly
     * hidden behind a password field costs a click, a value wrongly printed costs a rotation.</p>
     *
     * <p>The one exception is a key called just {@code key}: that is the ID of an entry - a
     * milestone, an objective, a sound - and hiding it blanks the title of every card that shows
     * one. A credential names what it opens ({@code api-key}); a schema's {@code @Secret} still
     * wins over this.</p>
     *
     * @param key the leaf key
     * @return whether the key contains {@code secret}, {@code token}, {@code password} or {@code key}
     */
    public static boolean isSecretKey(final String key) {
        final String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("key")) {
            return false;
        }
        return lower.contains("secret")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("key");
    }
}
