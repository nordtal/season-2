package eu.nordtal.jcore.config.internal;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Lets every single config value be overridden by an environment variable.
 * <p>
 * This is what joins the two config paths the payments-bot used to have: values from the file,
 * and scattered {@code System.getenv} calls that the file knew nothing about. Now the file
 * declares the setting and the environment can override it, which is what a container deploy
 * needs.
 * <p>
 * The environment always wins over the file, and an overridden value is <b>never</b> written
 * back - a secret handed in through the environment must not end up in a mounted volume.
 *
 * <h2>Naming</h2>
 * {@code <PREFIX>_<PATH>}, upper-cased, with both the path separator {@code .} and the hyphen
 * inside a key turned into {@code _}. So {@code nametag.display.text-shadow} with the prefix
 * {@code NORDTAL} is {@code NORDTAL_NAMETAG_DISPLAY_TEXT_SHADOW}.
 * <p>
 * Because two different characters collapse onto {@code _}, two distinct paths could in
 * principle produce the same variable name. Rather than resolving that at runtime, where it
 * would be an operator's problem, {@link #forSpec} rejects the spec outright: a collision is a
 * mistake in the interface and shows up the first time the config is loaded.
 */
public final class EnvOverlay {

    /** An empty value counts as unset, matching how the bot already treated POSTGRES_PASSWORD. */
    private final Map<String, SpecPaths.Leaf> byVariable;
    private final Function<String, String> environment;
    private final Gson gson;

    private EnvOverlay(final Map<String, SpecPaths.Leaf> byVariable,
                       final Function<String, String> environment,
                       final Gson gson) {
        this.byVariable = byVariable;
        this.environment = environment;
        this.gson = gson;
    }

    /**
     * Builds the overlay for a spec.
     *
     * @param specType    the spec interface
     * @param prefix      the variable prefix, e.g. {@code NORDTAL}
     * @param environment where variables are read from; {@link System#getenv(String)} in production
     * @param gson        used to parse values whose type is not a plain scalar
     * @throws IllegalStateException if two config paths map to the same variable name
     */
    public static @NotNull EnvOverlay forSpec(final @NotNull Class<?> specType,
                                              final @NotNull String prefix,
                                              final @NotNull Function<String, String> environment,
                                              final @NotNull Gson gson) {
        final Map<String, SpecPaths.Leaf> byVariable = new LinkedHashMap<>();
        final Map<String, List<String>> collisions = new TreeMap<>();

        for (SpecPaths.Leaf leaf : SpecPaths.leaves(specType)) {
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

    /** {@code nametag.display.text-shadow} + {@code NORDTAL} -> {@code NORDTAL_NAMETAG_DISPLAY_TEXT_SHADOW}. */
    public static @NotNull String variableName(final @NotNull String prefix, final @NotNull String path) {
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
     * @return the config paths that were overridden, in file order. The <b>values are
     * deliberately not returned or logged</b> - any one of them could be a secret.
     */
    public @NotNull List<String> applyTo(final @NotNull Object spec) {
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

    /** The variable name that overrides a given config path, for error messages and docs. */
    public @NotNull Map<String, SpecPaths.Leaf> variables() {
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
                // The overwhelmingly common list is a list of strings, and a comma-separated
                // value is far friendlier in a docker-compose file than JSON.
                return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
            return gson.fromJson(raw, leaf.genericType());
        } catch (NumberFormatException | JsonSyntaxException e) {
            // The value itself never appears in the message - it could be a secret.
            throw new IllegalArgumentException(
                    variable + " cannot be read as " + type.getSimpleName()
                            + " for config setting '" + leaf.path() + "'.", e);
        }
    }

    private static boolean isStringList(final SpecPaths.Leaf leaf) {
        return leaf.genericType() instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] == String.class;
    }

    private static boolean parseBoolean(final String raw, final String variable) {
        // Boolean.parseBoolean turns every typo into false, which is exactly the kind of silent
        // misconfiguration this whole change is about.
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
                    .reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException(variable + " must be one of: " + allowed + ".");
        }
    }
}
