package eu.nordtal.jcore.config.schema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One key of a {@code config.schema.json}, as {@link SchemaWriter} builds it from a
 * {@code @ConfigSpec} interface.
 * <p>
 * <b>Group is deliberately not a field here.</b> "Discord &rarr; Access" is not a second thing
 * this record has to say - it is {@code children} nested inside {@code children}, the same
 * nesting the YAML itself has. A caller that wants the group of a leaf setting walks the tree to
 * it; the alternative, a parallel group string computed independently, is the "second mechanism"
 * steward/50 explicitly rejected.
 * <p>
 * <b>Unit and value range are deliberately absent too</b> - both were proposed for this schema
 * and rejected (steward/50).
 *
 * @param kind                what sits under this key
 * @param label               the plain-language name, derived from the key the same way
 *                            steward-worker's {@code Labels.of} derives one from a raw YAML key -
 *                            {@code base-url} becomes {@code Base url}
 * @param explanation         the short text from {@link eu.nordtal.jcore.config.spec.annotation.Explain @Explain},
 *                            or the empty string if the property carries none. Never the long
 *                            {@code @Comment} text - that stays in the source for the person
 *                            reading the code and is never written here
 * @param noExplanationNeeded whether {@link eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded @NoExplanationNeeded}
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
 */
public record SchemaNode(
        @NotNull SettingKind kind,
        @NotNull String label,
        @NotNull String explanation,
        boolean noExplanationNeeded,
        boolean secret,
        @Nullable SettingType type,
        @Nullable Choices choices,
        @NotNull @Unmodifiable Map<String, SchemaNode> children
) {

    public SchemaNode {
        // Not Map.copyOf(): its iteration order is unspecified, and @Order is exactly what
        // Specs.from(...) already sorted this map by. Losing it here would put every setting
        // back in whatever order HashMap felt like, silently, only inside the schema.
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
    public record Choices(@NotNull @Unmodifiable List<String> values, boolean strict) {

        public Choices {
            values = List.copyOf(values);
        }
    }
}
