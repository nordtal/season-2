package eu.nordtal.jcore.config.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One key of a {@code config.schema.json}, as {@link SchemaWriter} builds it from a {@code @ConfigSpec} interface.
 *
 * <b>Group is deliberately not a field here.</b> "Discord &rarr; Access" is not a second thing
 * this record has to say - it is {@code children} nested inside {@code children}, the same
 * nesting the YAML itself has. A caller that wants the group of a leaf setting walks the tree to
 * it; the alternative, a parallel group string computed independently, is a second mechanism doing
 * the same job and was rejected for it.
 *
 * <b>Unit and value range are deliberately absent too</b> - both were proposed for this schema
 * and rejected.
 *
 * @param kind                what sits under this key
 * @param label               the plain-language name, derived from the key the same way
 *                            steward-worker's {@code Labels.of} derives one from a raw YAML key -
 *                            {@code base-url} becomes {@code Base url}. Always {@code ""} on the
 *                            root node, which stands for the file and has no key to derive one
 *                            from
 * @param explanation         the short text from {@link eu.nordtal.jcore.config.spec.annotation.Explain @Explain}
 *                            when the property carries one. When it does not - still most of the
 *                            codebase - the longer
 *                            {@link eu.nordtal.jcore.config.spec.annotation.Comment @Comment} text
 *                            is used instead, its lines joined with {@code '\n'}: a long
 *                            explanation nobody has shortened yet is better than an empty field,
 *                            and {@code @Explain} always wins once it is written. Empty only when
 *                            the property carries neither.
 *
 *                            <b>On the root node this is the file-level header</b> -
 *                            {@code @ConfigSpec(header = {...})}, one array entry per line, joined
 *                            with {@code '\n'}. It is therefore the one
 *                            place in this record where the text can be several lines and a whole
 *                            paragraph long, so a consumer that renders it must not assume one
 *                            line. Still {@code ""} when the spec declares no header
 * @param noExplanationNeeded whether
 *                            {@link eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded @NoExplanationNeeded}
 *                            is present - the interface shows no explanation at all rather than
 *                            an empty one
 * @param secret              whether {@link eu.nordtal.jcore.config.spec.annotation.Secret @Secret}
 *                            is present. This is the explicit declaration; the key-name heuristic
 *                            a consumer applies to every file, schema or not, is a separate net
 *                            underneath and is not replaced by this field
 * @param type                for a {@link SettingKind#SCALAR}, what the value looks like to YAML;
 *                            for a {@link SettingKind#LIST}, the type its entries share. Always
 *                            {@code null} for a {@link SettingKind#MAP}, which has no scalar of
 *                            its own
 * @param choices             the allowed (or suggested) values and whether the list is closed, or
 *                            {@code null} when this setting has none. Present for a Java
 *                            {@code enum}-typed property automatically, and for anything else
 *                            carrying {@link eu.nordtal.jcore.config.spec.annotation.AllowedValues @AllowedValues}
 * @param children            for a {@link SettingKind#MAP}, the nested settings, keyed by their
 *                            own leaf key; for a {@link SettingKind#LIST} of nested objects, the
 *                            shape of one element, the same way. Empty for a scalar list and for
 *                            a scalar setting
 * @param protectedEntry      for a {@link SettingKind#LIST} of nested objects whose property carries
 *                            {@link eu.nordtal.jcore.config.spec.annotation.Protected @Protected}:
 *                            the field and value that identify the one entry a consumer must refuse
 *                            to remove - {@code tag} / {@code en} for
 *                            {@code languages}, say. {@code null} whenever the property carries no
 *                            {@code @Protected}, and always {@code null} for a {@link SettingKind#SCALAR},
 *                            a {@link SettingKind#MAP} or a scalar list, none of which {@code @Protected}
 *                            is legal on - {@code SchemaWriter} refuses to build a schema that puts
 *                            it anywhere else. This is a description, not an enforcement: jcore
 *                            itself never refuses a removal, it only makes the rule readable to code
 *                            that does
 */
public record SchemaNode(
        SettingKind kind,
        String label,
        String explanation,
        boolean noExplanationNeeded,
        boolean secret,
        @Nullable SettingType type,
        @Nullable Choices choices,
        Map<String, SchemaNode> children,
        @Nullable ProtectedEntry protectedEntry) {

    /** Defensively copies {@code children} into an unmodifiable, order-preserving map. */
    public SchemaNode {
        // Not Map.copyOf(): its iteration order is unspecified, and @Order already sorted this map.
        children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
    }

    /**
     * The values offered for a setting, and whether the field next to them accepts free text.
     *
     * @param values the allowed (or suggested) values, in display order
     * @param strict {@code true} for a select with no free text; {@code false} for a select with
     *               a free-text field beside it - see
     *               {@link eu.nordtal.jcore.config.spec.annotation.AllowedValues#strict()}
     */
    public record Choices(List<String> values, boolean strict) {

        public Choices {
            values = List.copyOf(values);
        }
    }

    /**
     * Identifies the list entry a consumer must never remove; see {@code @Protected}.
     *
     * @param field the element's own field to match against, e.g. {@code tag}
     * @param value the value that field must equal for that entry to be the protected one
     */
    public record ProtectedEntry(String field, String value) {}
}
