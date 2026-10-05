package eu.nordtal.jcore.config.internal;

import eu.nordtal.jcore.config.spec.SpecClass;
import eu.nordtal.jcore.config.spec.SpecProperty;
import eu.nordtal.jcore.config.spec.Specs;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Finds keys in a loaded config file that the spec interface does not declare.
 *
 * A key that resembles a declared one reads as a slip of the keyboard: only the operator knows
 * what they meant, so it is reported rather than silently discarded. A key that resembles nothing
 * declared reads as retired: it no longer has a place in the spec. This walks the raw YAML tree
 * against the spec's property tree, descending into nested specs and into the elements of lists
 * of specs, and reports every unknown key with its full path and the closest declared key at that
 * level. See {@link UnknownKey#probableTypo()}.
 */
public final class UnknownKeyDetector {

    /** The largest edit distance at which a declared key is still offered as "did you mean". */
    private static final int MAX_SUGGESTION_DISTANCE = 3;

    private UnknownKeyDetector() {}

    /**
     * One unknown key.
     *
     * @param path       the full dotted path of the key
     * @param suggestion the declared key it most likely mistypes, or {@code null} if none is close
     * @param known      the declared keys at this level
     */
    public record UnknownKey(String path, @Nullable String suggestion, List<String> known) {

        /**
         * Whether this reads as a mistyped declared key rather than as a setting that has been removed from the spec.
         *
         * A typo is refused, because the operator meant something by that line and only they know
         * what. A key that resembles nothing declared has no such reading: the spec no longer has
         * it, so it is dropped from the file on the next write.
         *
         * @return {@code true} if this key reads as a mistyped declared key
         */
        public boolean probableTypo() {
            return suggestion != null;
        }

        /**
         * A human-readable description of this unknown key, suitable for a log line.
         *
         * @return the description
         */
        public String describe() {
            if (suggestion != null) {
                return "'" + path + "' is not a known setting - did you mean '" + suggestion + "'?";
            }
            return "'" + path + "' is not a known setting. Known settings at this level: " + String.join(", ", known)
                    + ".";
        }
    }

    /**
     * Collects every unknown key in {@code data}, in file order.
     *
     * @param specType the spec interface describing the expected shape
     * @param data     the raw YAML tree as loaded from the file
     * @return the unknown keys, empty if the file matches the spec
     */
    public static List<UnknownKey> detect(final Class<?> specType, final Map<String, Object> data) {
        final List<UnknownKey> found = new ArrayList<>();
        walk(specType, data, "", found);
        return found;
    }

    private static void walk(
            final Class<?> specType,
            final Map<String, Object> data,
            final String prefix,
            final List<UnknownKey> found) {
        final SpecClass spec = Specs.from(specType);
        final Map<String, SpecProperty> properties = new LinkedHashMap<>();
        for (final SpecProperty property : spec.properties().values()) {
            // Save/reload/reset methods are proxy plumbing, not file keys.
            if (!property.isHandledByProxy()) {
                properties.put(property.key(), property);
            }
        }

        for (final Map.Entry<String, Object> entry : data.entrySet()) {
            final String key = entry.getKey();
            final String path = prefix.isEmpty() ? key : prefix + "." + key;
            final SpecProperty property = properties.get(key);

            if (property == null) {
                final List<String> known = List.copyOf(properties.keySet());
                found.add(new UnknownKey(path, suggest(key, known), known));
                continue;
            }

            final Object value = entry.getValue();
            if (Specs.isConfigSpec(property.type()) && value instanceof Map<?, ?> nested) {
                walk(property.type(), cast(nested), path, found);
                continue;
            }

            final Class<?> element = elementTypeOf(property);
            if (element != null && Specs.isConfigSpec(element) && value instanceof Collection<?> items) {
                int index = 0;
                for (final Object item : items) {
                    if (item instanceof Map<?, ?> itemMap) {
                        walk(element, cast(itemMap), path + "[" + index + "]", found);
                    }
                    index++;
                }
            }
        }
    }

    /**
     * The element type of a list-valued or array-valued property, or {@code null} if the property is neither.
     */
    private static @Nullable Class<?> elementTypeOf(final SpecProperty property) {
        final Class<?> raw = property.type();
        if (raw.isArray()) {
            return raw.getComponentType();
        }
        if (!Collection.class.isAssignableFrom(raw)) {
            return null;
        }
        final Type generic = property.getter().getGenericReturnType();
        if (generic instanceof ParameterizedType parameterized) {
            final Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * The declared key closest to {@code key} by edit distance, or {@code null} when nothing is close enough.
     *
     * @param key the unknown key read from the file
     * @param candidates the declared keys at this level
     * @return the closest declared key, or {@code null}
     */
    public static @Nullable String suggest(final String key, final Collection<String> candidates) {
        @Nullable String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final String candidate : candidates) {
            final int distance =
                    levenshtein(key.toLowerCase(Locale.getDefault()), candidate.toLowerCase(Locale.getDefault()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        // Scale the threshold with key length: a short key cannot match anything by accident.
        final int limit = Math.min(MAX_SUGGESTION_DISTANCE, Math.max(1, key.length() / 3));
        return bestDistance <= limit ? best : null;
    }

    static int levenshtein(final String a, final String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
