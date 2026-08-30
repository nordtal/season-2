package eu.nordtal.jcore.config.internal;

import eu.nordtal.jcore.config.spec.SpecClass;
import eu.nordtal.jcore.config.spec.SpecProperty;
import eu.nordtal.jcore.config.spec.Specs;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds keys in a loaded config file that the spec interface does not declare.
 * <p>
 * The old loader deleted them silently: a typo made the value fall back to a default
 * <i>and</i> removed the operator's line from the file, with no warning and no backup. Inside a
 * list element it was worse - the value fell back to the default but the mistyped line stayed
 * visibly in the file, so the operator kept reading a setting that never took effect.
 * <p>
 * This walks the raw YAML tree against the spec's property tree, descends into nested specs
 * <b>and into the elements of lists of specs</b>, and reports every unknown key with its full
 * path and the closest declared key at that level.
 */
public final class UnknownKeyDetector {

    /**
     * The largest edit distance at which a declared key is still offered as "did you mean".
     * Two edits catches ordinary typos and transpositions without suggesting an unrelated key.
     */
    private static final int MAX_SUGGESTION_DISTANCE = 3;

    private UnknownKeyDetector() {
    }

    /** One unknown key, with its full dotted path and the best guess at what was meant. */
    public record UnknownKey(@NotNull String path, String suggestion, @NotNull List<String> known) {

        public @NotNull String describe() {
            if (suggestion != null) {
                return "'" + path + "' is not a known setting - did you mean '" + suggestion + "'?";
            }
            return "'" + path + "' is not a known setting. Known settings at this level: "
                    + String.join(", ", known) + ".";
        }
    }

    /**
     * Collects every unknown key in {@code data}, in file order.
     *
     * @param specType the spec interface describing the expected shape
     * @param data     the raw YAML tree as loaded from the file
     * @return the unknown keys, empty if the file matches the spec
     */
    public static @NotNull List<UnknownKey> detect(final @NotNull Class<?> specType,
                                                   final @NotNull Map<String, Object> data) {
        final List<UnknownKey> found = new ArrayList<>();
        walk(specType, data, "", found);
        return found;
    }

    private static void walk(final Class<?> specType,
                             final Map<String, Object> data,
                             final String prefix,
                             final List<UnknownKey> found) {
        final SpecClass spec = Specs.from(specType);
        final Map<String, SpecProperty> properties = new LinkedHashMap<>();
        for (SpecProperty property : spec.properties().values()) {
            // Save/reload/reset methods are proxy plumbing, not file keys.
            if (!property.isHandledByProxy()) {
                properties.put(property.key(), property);
            }
        }

        for (Map.Entry<String, Object> entry : data.entrySet()) {
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

            // Lists of nested specs. This is the case the old loader's diff was completely blind
            // to, and the one that produced a wrong value that looked correct in the file.
            final Class<?> element = elementTypeOf(property);
            if (element != null && Specs.isConfigSpec(element) && value instanceof Collection<?> items) {
                int index = 0;
                for (Object item : items) {
                    if (item instanceof Map<?, ?> itemMap) {
                        walk(element, cast(itemMap), path + "[" + index + "]", found);
                    }
                    index++;
                }
            }
        }
    }

    /**
     * The element type of a list-valued or array-valued property, or {@code null} if the
     * property is neither.
     */
    private static Class<?> elementTypeOf(final SpecProperty property) {
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
     * The declared key closest to {@code key} by edit distance, or {@code null} when nothing is
     * close enough to be a useful guess.
     */
    public static String suggest(final @NotNull String key, final @NotNull Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            final int distance = levenshtein(key.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        // Scale the threshold with the key length so a short key cannot match anything and a
        // long one tolerates a couple of slips.
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
