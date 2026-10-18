package eu.nordtal.season.settings;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.season.spec.SpecPaths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Overrides values of a spec with environment variables. A variable wins and is never written back.
 * Its name is {@code <PREFIX>_<PATH>}, upper-cased, dots and dashes as {@code _}.
 */
public final class EnvOverlay {

    private final Map<String, SpecPaths.Leaf> byVariable;

    private final Function<String, String> environment;
    private final Gson gson;

    private EnvOverlay(
            final Map<String, SpecPaths.Leaf> byVariable, final Function<String, String> environment, final Gson gson) {
        this.byVariable = byVariable;
        this.environment = environment;
        this.gson = gson;
    }

    /**
     * @throws IllegalStateException if two config paths map to the same variable name
     */
    public static EnvOverlay forSpec(
            final Class<?> specType, final String prefix, final Function<String, String> environment, final Gson gson) {
        final Map<String, SpecPaths.Leaf> byVariable = new LinkedHashMap<>();
        final Map<String, List<String>> collisions = new TreeMap<>();

        for (final SpecPaths.Leaf leaf : SpecPaths.leaves(specType)) {
            final String variable = variableName(prefix, leaf.path());
            collisions.computeIfAbsent(variable, k -> new ArrayList<>()).add(leaf.path());
            byVariable.put(variable, leaf);
        }

        final List<String> clashing = new ArrayList<>();
        collisions.forEach((variable, paths) -> {
            if (paths.size() > 1) {
                clashing.add(variable + " <- " + String.join(", ", paths));
            }
        });
        if (!clashing.isEmpty()) {
            throw new IllegalStateException(
                    "Config spec " + specType.getName() + " has settings whose environment variable "
                            + "names collide. Rename one of each pair:" + System.lineSeparator()
                            + String.join(System.lineSeparator(), clashing));
        }
        return new EnvOverlay(byVariable, environment, gson);
    }

    /** Returns the variable name of a path.
     * The path {@code nametags.text-shadow} with the prefix {@code NORDTAL} gives {@code NORDTAL_NAMETAGS_TEXT_SHADOW}.
     */
    public static String variableName(final String prefix, final String path) {
        final StringBuilder name = new StringBuilder(prefix.toUpperCase(Locale.ROOT));
        name.append('_');
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            name.append(c == '.' || c == '-' ? '_' : Character.toUpperCase(c));
        }
        return name.toString();
    }

    /**
     * Applies every variable that is set and non-blank to {@code spec}.
     *
     * @return the overridden config paths in file order, never their values, which could be secrets
     */
    public List<String> applyTo(final Object spec) {
        final List<String> overridden = new ArrayList<>();
        byVariable.forEach((variable, leaf) -> {
            final String raw = environment.apply(variable);
            if (raw == null || raw.isBlank()) {
                return;
            }
            SpecPaths.set(spec, leaf.path(), convert(raw.trim(), leaf, variable));
            overridden.add(leaf.path());
        });
        return overridden;
    }

    public Map<String, SpecPaths.Leaf> variables() {
        return Map.copyOf(byVariable);
    }

    private Object convert(final String raw, final SpecPaths.Leaf leaf, final String variable) {
        final Class<?> type = leaf.type();
        try {
            if (type == String.class) return raw;
            if (type == boolean.class || type == Boolean.class) return parseBoolean(raw, variable);
            if (type == byte.class || type == Byte.class) return Byte.parseByte(raw);
            if (type == short.class || type == Short.class) return Short.parseShort(raw);
            if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
            if (type == long.class || type == Long.class) return Long.parseLong(raw);
            if (type == float.class || type == Float.class) return Float.parseFloat(raw);
            if (type == double.class || type == Double.class) return Double.parseDouble(raw);
            if (type.isEnum()) return parseEnum(type, raw, variable);
            if (List.class.isAssignableFrom(type) && isStringList(leaf)) {
                return Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
            return gson.fromJson(raw, leaf.genericType());
        } catch (NumberFormatException | JsonSyntaxException e) {
            // The message omits the value because it could be a secret.
            throw new IllegalArgumentException(
                    variable + " cannot be read as " + type.getSimpleName() + " for config setting '" + leaf.path()
                            + "'.",
                    e);
        }
    }

    private static boolean isStringList(final SpecPaths.Leaf leaf) {
        return leaf.genericType() instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] == String.class;
    }

    private static boolean parseBoolean(final String raw, final String variable) {
        // Boolean.parseBoolean turns any typo into false.
        final String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("yes") || value.equals("1")) return true;
        if (value.equals("false") || value.equals("no") || value.equals("0")) return false;
        throw new IllegalArgumentException(variable + " must be true or false.");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseEnum(final Class<?> type, final String raw, final String variable) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            final String allowed = Arrays.stream(type.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name().toLowerCase(Locale.ROOT))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new IllegalArgumentException(variable + " must be one of: " + allowed + ".");
        }
    }
}
