package eu.nordtal.season.spec.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One setting of a schema, as {@link SchemaWriter} builds it from a {@code @ConfigSpec} interface.
 *
 * @param kind what sits under this key
 * @param label the name derived from the key, {@code base-url} as {@code Base url}; {@code ""} on the root
 * @param explanation the {@code @Explain} text, else the {@code @Comment} lines joined with {@code '\n'}, else
 *     {@code ""}; on the root, the spec's header, which may be several paragraphs
 * @param noExplanationNeeded whether {@code @NoExplanationNeeded} is present, so no explanation is shown at all
 * @param secret whether {@code @Secret} is present
 * @param type for a scalar the type of its value, for a list the type its entries share; {@code null} for a map
 * @param choices the allowed or suggested values and whether the list is closed, or {@code null} for none
 * @param children a map's nested settings, or the shape of one element of a list of nested specs; else empty
 * @param protectedEntry for a list of nested specs carrying {@code @Protected}, the entry a consumer must not
 *     remove; else {@code null}
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
     * @param strict {@code true} for a closed list, {@code false} for suggestions beside a free-text field
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
