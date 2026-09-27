package eu.nordtal.s2.steward.worker.configfile;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One key of a jcore-written config file, as the interface draws it, read from the file and its schema.
 *
 * @param path the dotted path from the document root, for example {@code worker.base-url}
 * @param key the leaf key on its own, for example {@code base-url}
 * @param label the leaf key as a human reads it, from the schema or else {@link Labels#of(String)}
 * @param comments the {@code #} comment lines directly above the key, without {@code # }; empty when there are none
 * @param explanation the schema's {@code @Explain} text, or the empty string without a schema entry
 * @param noExplanationNeeded whether the schema says this setting needs no explanation, drawn as no text at all
 * @param value the scalar as it stands in the file, unquoted and unescaped; empty for a list or a map
 * @param items the entries of a {@link Kind#LIST}, in file order and unquoted; empty for every other kind
 * @param template the blank field set an "Add" starts from, for a uniform {@link Kind#SECTIONS} schema; else empty
 * @param sections one list of field entries per existing section, in file order; empty for every other kind
 * @param kind what sits under the key, always read from the file
 * @param type the scalar's YAML type, or the type a list's entries share; {@link Type#STRING} otherwise
 * @param line the 1-based line the key sits on, for an error message
 * @param editable whether {@link ConfigFiles#write} accepts a change to this key
 * @param secret whether this key holds a credential, by the schema's {@code @Secret} or {@link #isSecretKey(String)}
 * @param inSchema whether the schema declares this key; always {@code true} for a file without a schema
 * @param environmentOverridden whether an environment variable overrides this path, or {@code null} when unknown
 * @param choices the allowed or suggested values from the schema, or {@code null}
 * @param protectedEntry the field and value of the section a save must never remove, or {@code null}
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
        /** A YAML sequence whose every entry is itself a mapping, drawn as one card per entry. */
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
     * The allowed or suggested values of a setting, from its schema.
     *
     * @param values the values, in the order the schema gives them
     * @param strict {@code true} for a closed list; {@code false} for a select with a free-text field beside it
     */
    public record Choices(List<String> values, boolean strict) {

        public Choices {
            values = List.copyOf(values);
        }
    }

    /**
     * Identifies the one section of a {@link Kind#SECTIONS} list that a save must refuse to remove.
     *
     * @param field the field key within one section to match against, for example {@code tag}
     * @param value the value that field must equal for that section to be the protected one
     */
    public record Protected(String field, String value) {}

    public ConfigEntry {
        comments = List.copyOf(comments);
        items = List.copyOf(items);
        template = List.copyOf(template);
        sections = sections.stream().map(List::copyOf).toList();
    }

    /**
     * Returns whether a leaf key names something that must not be shown or logged in the clear.
     *
     * @param key the leaf key
     * @return whether it contains {@code secret}, {@code token}, {@code password} or {@code key}, other than a bare
     *     {@code key}
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
