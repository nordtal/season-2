package eu.nordtal.season.spec.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A setting of a schema, which {@link SchemaWriter} builds from a {@code @ConfigSpec} interface.
 *
 * @param kind the kind of value under this key
 * @param label the name derived from the key, such as {@code Base URL} for {@code base-url}, and {@code ""} on the root
 * @param explanation the {@code @Explain} text, else {@code ""}. On the root it is the header of the spec
 * @param noExplanationNeeded whether {@code @NoExplanationNeeded} is present, so that no explanation shows
 * @param secret whether {@code @Secret} is present
 * @param type the type of the value for a scalar, the type of the entries for a list, {@code null} for a map
 * @param choices the allowed or suggested values and whether the list is closed, or {@code null} for none
 * @param children the nested settings of a map, or the shape of one element of a list of nested specs, else empty
 * @param protectedEntry the entry that {@code @Protected} marks and a consumer never removes, else {@code null}
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

    /** Copies {@code children} into an unmodifiable map that keeps the order. */
    public SchemaNode {
        // Map.copyOf() does not keep the order, and @Order has already sorted this map.
        children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
    }

    /**
     * The values that a setting offers and whether the field beside them accepts free text.
     *
     * @param values the allowed or suggested values, in display order
     * @param strict {@code true} for a closed list, {@code false} for suggestions beside a free-text field
     */
    public record Choices(List<String> values, boolean strict) {

        public Choices {
            values = List.copyOf(values);
        }
    }

    /**
     * Identifies the list entry that a consumer never removes (see {@code @Protected}).
     *
     * @param field the field of the entry to compare, such as {@code tag}
     * @param value the value that the field has in the protected entry
     */
    public record ProtectedEntry(String field, String value) {}
}
