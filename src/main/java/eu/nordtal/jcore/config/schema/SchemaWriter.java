package eu.nordtal.jcore.config.schema;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.internal.AtomicConfigWriter;
import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.SpecProperty;
import eu.nordtal.jcore.config.spec.annotation.AllowedValues;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code <name>.schema.json} beside {@code <name>.yml} - the same operation that writes
 * the YAML writes this too, so the two cannot drift apart the way a schema kept somewhere else
 * could (steward/50, steward/54).
 * <p>
 * Every setting the schema carries: the plain-language name, the allowed values (and whether the
 * list is closed or a suggestion), whether no explanation is needed, whether it is a secret, the
 * short explanation text, and the type and kind - already exactly what
 * {@link SchemaNode} documents. The group is not a separate field; it is the nesting of the
 * schema tree itself, which mirrors the YAML's own nesting.
 */
public final class SchemaWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SchemaWriter() {
    }

    /**
     * Builds the schema tree for a {@code @ConfigSpec} interface, without touching a file.
     *
     * @param specType the spec interface
     * @return the root node, always {@link SettingKind#MAP}
     */
    public static @NotNull SchemaNode build(final @NotNull Class<?> specType) {
        return new SchemaNode(SettingKind.MAP, "", "", false, false, null, null, childrenOf(specType));
    }

    /**
     * The path {@link #write} uses for a given YAML file: the same directory, {@code .yml} (or
     * {@code .yaml}) replaced with {@code .schema.json}.
     *
     * @param ymlFile the configuration file
     * @return the sibling schema file
     */
    public static @NotNull Path schemaFileFor(final @NotNull Path ymlFile) {
        final String name = ymlFile.getFileName().toString();
        final String base;
        if (name.endsWith(".yml")) {
            base = name.substring(0, name.length() - ".yml".length());
        } else if (name.endsWith(".yaml")) {
            base = name.substring(0, name.length() - ".yaml".length());
        } else {
            base = name;
        }
        return ymlFile.resolveSibling(base + ".schema.json");
    }

    /**
     * Builds the schema for {@code specType} and writes it next to {@code ymlFile}, atomically.
     *
     * @param ymlFile  the configuration file the schema describes
     * @param specType the spec interface
     */
    public static void write(final @NotNull Path ymlFile, final @NotNull Class<?> specType) {
        final String json = GSON.toJson(build(specType));
        AtomicConfigWriter.write(schemaFileFor(ymlFile), json);
    }

    /**
     * Asserts that {@code ymlFile} and its schema exist together, or that neither does.
     * <p>
     * {@link #write} always writes both in the same call, so this can only fail if something
     * outside this class removed one of the two afterwards - which is exactly the state steward/54
     * calls an error rather than a state to render around: a schema with no file behind it
     * describes nothing real, and a file with no schema is exactly the staleness the whole
     * arrangement exists to prevent.
     *
     * @param ymlFile the configuration file
     * @throws ConfigException naming whichever of the two is missing
     */
    public static void checkPaired(final @NotNull Path ymlFile) throws ConfigException {
        final Path schemaFile = schemaFileFor(ymlFile);
        final boolean fileExists = Files.isRegularFile(ymlFile);
        final boolean schemaExists = Files.isRegularFile(schemaFile);
        if (fileExists && !schemaExists) {
            throw new ConfigException(
                    "Config file " + ymlFile + " exists but its schema " + schemaFile + " does not.");
        }
        if (schemaExists && !fileExists) {
            throw new ConfigException(
                    "Schema " + schemaFile + " exists but its config file " + ymlFile + " does not.");
        }
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Map<String, SchemaNode> childrenOf(final @NotNull Class<?> specType) {
        final Map<String, SchemaNode> children = new LinkedHashMap<>();
        for (final SpecProperty property : Specs.from(specType).properties().values()) {
            // @Reload / @Save / @AsMap: proxy-handled accessors, not a YAML key of their own.
            if (property.isHandledByProxy()) {
                continue;
            }
            children.put(property.key(), nodeFor(property));
        }
        return children;
    }

    private static @NotNull SchemaNode nodeFor(final @NotNull SpecProperty property) {
        final Method getter = property.getter();
        final Explain explain = getter.getAnnotation(Explain.class);
        final NoExplanationNeeded noExplanationNeeded = getter.getAnnotation(NoExplanationNeeded.class);
        if (explain != null && noExplanationNeeded != null) {
            throw new IllegalArgumentException(
                    "Property '" + property.key() + "' carries both @Explain and"
                            + " @NoExplanationNeeded - decide which one this setting means.");
        }
        final String explanation = explain != null ? explain.value() : "";
        final boolean skipExplanation = noExplanationNeeded != null;
        final boolean secret = getter.isAnnotationPresent(Secret.class);
        final String label = SettingLabels.of(property.key());
        final Class<?> type = property.type();

        if (Specs.isConfigSpec(type)) {
            return new SchemaNode(SettingKind.MAP, label, explanation, skipExplanation, secret,
                    null, null, childrenOf(type));
        }
        if (isCollection(type)) {
            final Class<?> elementType = collectionElementType(getter);
            if (Specs.isConfigSpec(elementType)) {
                return new SchemaNode(SettingKind.LIST, label, explanation, skipExplanation, secret,
                        null, null, childrenOf(elementType));
            }
            return new SchemaNode(SettingKind.LIST, label, explanation, skipExplanation, secret,
                    scalarTypeOf(elementType), choicesOf(getter, elementType), Map.of());
        }
        return new SchemaNode(SettingKind.SCALAR, label, explanation, skipExplanation, secret,
                scalarTypeOf(type), choicesOf(getter, type), Map.of());
    }

    private static boolean isCollection(final @NotNull Class<?> type) {
        return Collection.class.isAssignableFrom(type) || type.isArray();
    }

    /**
     * The element type of a list-shaped property, mirroring
     * {@code ConfigEntry}'s own rule: the type its entries share, or {@code String} when they are
     * mixed, generic or there is no declared element type to read.
     */
    private static @NotNull Class<?> collectionElementType(final @NotNull Method getter) {
        if (getter.getReturnType().isArray()) {
            return getter.getReturnType().getComponentType();
        }
        final Type generic = getter.getGenericReturnType();
        if (generic instanceof ParameterizedType parameterized) {
            final Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        return String.class;
    }

    private static @NotNull SettingType scalarTypeOf(final @NotNull Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return SettingType.BOOLEAN;
        }
        if (type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            return SettingType.INTEGER;
        }
        if (type == float.class || type == Float.class
                || type == double.class || type == Double.class) {
            return SettingType.DECIMAL;
        }
        // String, char, a Java enum, and anything else not covered above.
        return SettingType.STRING;
    }

    private static SchemaNode.Choices choicesOf(final @NotNull Method getter, final @NotNull Class<?> type) {
        final AllowedValues allowedValues = getter.getAnnotation(AllowedValues.class);
        if (type.isEnum()) {
            final List<String> values = allowedValues != null
                    ? List.of(allowedValues.value())
                    : Arrays.stream(type.getEnumConstants())
                            .map(constant -> ((Enum<?>) constant).name())
                            .toList();
            // A Java enum can never deserialize a free-text value, whatever @AllowedValues says.
            return new SchemaNode.Choices(values, true);
        }
        if (allowedValues != null) {
            return new SchemaNode.Choices(List.of(allowedValues.value()), allowedValues.strict());
        }
        return null;
    }
}
