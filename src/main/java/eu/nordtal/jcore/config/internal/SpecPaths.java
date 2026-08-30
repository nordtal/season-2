package eu.nordtal.jcore.config.internal;

import eu.nordtal.jcore.config.spec.SpecClass;
import eu.nordtal.jcore.config.spec.SpecProperty;
import eu.nordtal.jcore.config.spec.Specs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Addresses the leaves of a spec by their dotted config path ({@code nametag.display.scale}).
 * <p>
 * A spec instance is a proxy backed by a {@link Map}, and a nested spec is another such proxy
 * stored as a value in it. Reading or writing one value by path therefore means walking those
 * maps, which is what this does.
 */
public final class SpecPaths {

    private SpecPaths() {
    }

    /** A single settable value in a spec, identified by its dotted path. */
    public record Leaf(@NotNull String path, @NotNull Class<?> type, @NotNull Type genericType) {
    }

    /**
     * Every leaf of {@code specType}, in declaration order. Nested specs are descended into;
     * they are containers, not values, so they are not leaves themselves.
     */
    public static @NotNull List<Leaf> leaves(final @NotNull Class<?> specType) {
        final List<Leaf> leaves = new ArrayList<>();
        collect(specType, "", leaves);
        return leaves;
    }

    private static void collect(final Class<?> specType, final String prefix, final List<Leaf> leaves) {
        final SpecClass spec = Specs.from(specType);
        for (SpecProperty property : spec.properties().values()) {
            if (property.isHandledByProxy()) {
                continue;
            }
            final String path = prefix.isEmpty() ? property.key() : prefix + "." + property.key();
            if (Specs.isConfigSpec(property.type())) {
                collect(property.type(), path, leaves);
            } else {
                leaves.add(new Leaf(path, property.type(), property.getter().getGenericReturnType()));
            }
        }
    }

    /**
     * Reads the value at {@code path} from a spec instance.
     *
     * @throws IllegalArgumentException if the path does not exist in the spec
     */
    public static @Nullable Object get(final @NotNull Object spec, final @NotNull String path) {
        final String[] segments = path.split("\\.");
        Map<String, Object> map = Specs.getInternalMap(spec);
        for (int i = 0; i < segments.length - 1; i++) {
            final Object nested = map.get(segments[i]);
            if (nested == null) {
                throw new IllegalArgumentException("No value at '" + path + "': '" + segments[i] + "' is absent.");
            }
            map = Specs.getInternalMap(nested);
        }
        return map.get(segments[segments.length - 1]);
    }

    /**
     * Writes {@code value} at {@code path} into a spec instance.
     *
     * @throws IllegalArgumentException if the path does not exist in the spec
     */
    public static void set(final @NotNull Object spec, final @NotNull String path, final @Nullable Object value) {
        final String[] segments = path.split("\\.");
        Map<String, Object> map = Specs.getInternalMap(spec);
        for (int i = 0; i < segments.length - 1; i++) {
            final Object nested = map.get(segments[i]);
            if (nested == null) {
                throw new IllegalArgumentException("Cannot set '" + path + "': '" + segments[i] + "' is absent.");
            }
            map = Specs.getInternalMap(nested);
        }
        map.put(segments[segments.length - 1], value);
    }

    /** A shallow snapshot of the values at the given paths, for restoring them later. */
    public static @NotNull Map<String, Object> snapshot(final @NotNull Object spec,
                                                        final @NotNull Iterable<String> paths) {
        final Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String path : paths) {
            snapshot.put(path, get(spec, path));
        }
        return snapshot;
    }
}
