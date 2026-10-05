package eu.nordtal.jcore.config.schema;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.nordtal.jcore.config.AtomicConfigWriter;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.spec.SpecProperty;
import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.AllowedValues;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.jcore.config.spec.annotation.Secret;
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
import org.jspecify.annotations.Nullable;

/**
 * Writes {@code <name>.schema.json} beside {@code <name>.yml}, in the same operation that writes the YAML.
 *
 * Every setting the schema carries: the plain-language name, the allowed values (and whether the
 * list is closed or a suggestion), whether no explanation is needed, whether it is a secret, the
 * short explanation text, and the type and kind - already exactly what
 * {@link SchemaNode} documents. The group is not a separate field; it is the nesting of the
 * schema tree itself, which mirrors the YAML's own nesting.
 *
 * The file-level {@code @ConfigSpec(header = {...})} is the root node's {@code explanation} - see
 * {@link #headerOf}. Nothing else writes the header anywhere; it is otherwise carried in no file
 * at all.
 *
 * A list-of-settings property carrying {@code @Protected} gets a
 * {@link SchemaNode#protectedEntry()} naming the one entry a consumer must never let an operator
 * remove - the shape a list's own entries take is otherwise all a schema can describe, never
 * a rule about one specific value among them.
 */
public final class SchemaWriter {

    /**
     * Pretty-printed, and <b>not</b> HTML-escaped.
     *
     * Gson escapes apostrophes, angle brackets and ampersands by default, for a JSON document
     * that is about to be pasted into HTML. A schema file is not: it is written beside a config
     * file and read by a JVM, and the only other reader is a person opening it to see what the
     * shape is. Leaving Gson's default escaping on turns every apostrophe in a header or
     * explanation into its numeric character reference, which is legal JSON, correct on screen,
     * and unreadable in the file itself.
     */
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private SchemaWriter() {}

    /**
     * Builds the schema tree for a {@code @ConfigSpec} interface, without touching a file.
     *
     * @param specType the spec interface
     * @return the root node, always {@link SettingKind#MAP}
     */
    public static SchemaNode build(final Class<?> specType) {
        return new SchemaNode(
                SettingKind.MAP, "", headerOf(specType), false, false, null, null, childrenOf(specType), null);
    }

    /**
     * The path {@link #write} uses for a given YAML file: the directory, with {@code .schema.json} for the extension.
     *
     * @param ymlFile the configuration file
     * @return the sibling schema file
     */
    public static Path schemaFileFor(final Path ymlFile) {
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
    public static void write(final Path ymlFile, final Class<?> specType) {
        final String json = GSON.toJson(build(specType));
        AtomicConfigWriter.write(schemaFileFor(ymlFile), json);
    }

    /**
     * Asserts that {@code ymlFile} and its schema exist together, or that neither does.
     *
     * {@link #write} always writes both in the same call, so this can only fail if something
     * outside this class removed one of the two afterwards - which is an error rather than a state
     * to render around: a schema with no file behind it
     * describes nothing real, and a file with no schema is exactly the staleness the whole
     * arrangement exists to prevent.
     *
     * @param ymlFile the configuration file
     * @throws ConfigException naming whichever of the two is missing
     */
    public static void checkPaired(final Path ymlFile) throws ConfigException {
        final Path schemaFile = schemaFileFor(ymlFile);
        final boolean fileExists = Files.isRegularFile(ymlFile);
        final boolean schemaExists = Files.isRegularFile(schemaFile);
        if (fileExists && !schemaExists) {
            throw new ConfigException("Config file " + ymlFile + " exists but its schema " + schemaFile + " does not.");
        }
        if (schemaExists && !fileExists) {
            throw new ConfigException("Schema " + schemaFile + " exists but its config file " + ymlFile + " does not.");
        }
    }

    /**
     * The file-level {@code @ConfigSpec(header = {...})} as one block of text, or empty when the spec declares none.
     *
     * The per-key {@code @Explain} text goes into this schema; {@code @Comment} reaches no other
     * file, so {@link #nodeFor} falls back to it for the (still common) case where a property
     * has no {@code @Explain} of its own. The file-level header goes nowhere else either, so it
     * is written into no file but this one. That is not a cosmetic detail:
     * season-2's {@code BotSpec} uses its header for the only sentence anywhere that tells an
     * operator the Discord token and the bunq key come from {@code NORDTAL_BOT_TOKEN} and friends
     * rather than from the file they are looking at.
     *
     * The root node is where it belongs, because a header describes the whole file exactly as the
     * root node does, and because it needs no new field that every consumer would then have to
     * learn about: anything already rendering {@code explanation} renders this for free.
     *
     * It lands on {@code explanation} and deliberately <b>not</b> on {@code label}. A label is a
     * name - {@code SettingLabels.of} turns {@code base-url} into {@code Base url}, two or three
     * words meant for a heading - and a header is prose, up to a dozen lines of it. Putting a
     * paragraph where a consumer expects a heading would break every caller that renders one.
     *
     * An absent or empty header stays the empty string, which is what {@code explanation} has
     * always been for a node with nothing to say, so nothing downstream has to change and no
     * placeholder text is invented.
     *
     * {@code headerOf} has already split every array entry on {@code '\n'}, so joining the result
     * with {@code '\n'} returns the author's text unchanged rather than doubling a line break.
     * Blank entries are kept: {@code BotSpec}'s header is paragraphs separated by empty lines, and
     * dropping them would run all of it together. The text is otherwise taken verbatim - the
     * {@code '# '} prefix and the {@code '#'} separator convention documented on
     * {@link eu.nordtal.jcore.config.spec.annotation.ConfigSpec#header()} are how the header used
     * to be <i>rendered into YAML</i>, and that rendering is gone; re-applying any of it here
     * would put comment syntax into a JSON string that no YAML parser will ever see.
     *
     * @param specType the spec interface
     * @return the header text, or {@code ""}
     */
    private static String headerOf(final Class<?> specType) {
        return String.join("\n", Specs.from(specType).headers());
    }

    private static Map<String, SchemaNode> childrenOf(final Class<?> specType) {
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

    /**
     * {@code @Explain} wins when present; otherwise {@code @Comment}'s text is used, so the field is never left empty.
     *
     * @param property the property to read {@code @Explain}/{@code @Comment}/{@code @NoExplanationNeeded} off
     * @return the explanation text and whether a missing one is deliberate
     */
    private static ExplanationInfo explanationOf(final SpecProperty property) {
        final Method getter = property.getter();
        final Explain explain = getter.getAnnotation(Explain.class);
        final Comment comment = getter.getAnnotation(Comment.class);
        final NoExplanationNeeded noExplanationNeeded = getter.getAnnotation(NoExplanationNeeded.class);
        if (explain != null && noExplanationNeeded != null) {
            throw new IllegalArgumentException("Property '" + property.key() + "' carries both @Explain and"
                    + " @NoExplanationNeeded - decide which one this setting means.");
        }
        final String explanation =
                explain != null ? explain.value() : comment != null ? String.join("\n", comment.value()) : "";
        return new ExplanationInfo(explanation, noExplanationNeeded != null);
    }

    /** The result of {@link #explanationOf}. */
    private record ExplanationInfo(String explanation, boolean skipExplanation) {}

    private static SchemaNode nodeFor(final SpecProperty property) {
        final Method getter = property.getter();
        final ExplanationInfo explanationInfo = explanationOf(property);
        final String explanation = explanationInfo.explanation();
        final boolean skipExplanation = explanationInfo.skipExplanation();
        final boolean secret = getter.isAnnotationPresent(Secret.class);
        final String label = labelOf(property);
        final Class<?> type = property.type();
        final @Nullable Protected protectedAnnotation = getter.getAnnotation(Protected.class);

        if (Specs.isConfigSpec(type)) {
            refuseProtectedOutsideAListOfSettings(protectedAnnotation, property.key());
            return new SchemaNode(
                    SettingKind.MAP, label, explanation, skipExplanation, secret, null, null, childrenOf(type), null);
        }
        if (isCollection(type)) {
            final Class<?> elementType = collectionElementType(getter);
            if (Specs.isConfigSpec(elementType)) {
                return new SchemaNode(
                        SettingKind.LIST,
                        label,
                        explanation,
                        skipExplanation,
                        secret,
                        null,
                        null,
                        childrenOf(elementType),
                        protectedEntryOf(protectedAnnotation, elementType, property.key()));
            }
            refuseProtectedOutsideAListOfSettings(protectedAnnotation, property.key());
            return new SchemaNode(
                    SettingKind.LIST,
                    label,
                    explanation,
                    skipExplanation,
                    secret,
                    scalarTypeOf(elementType),
                    choicesOf(getter, elementType),
                    Map.of(),
                    null);
        }
        refuseProtectedOutsideAListOfSettings(protectedAnnotation, property.key());
        return new SchemaNode(
                SettingKind.SCALAR,
                label,
                explanation,
                skipExplanation,
                secret,
                scalarTypeOf(type),
                choicesOf(getter, type),
                Map.of(),
                null);
    }

    /**
     * The name a setting is shown under: the getter's {@code @Name}, the nested interface's {@code @Name}, or the key.
     *
     * A list of sections does not take the interface's name: that names one entry, not the list.
     */
    static String labelOf(final SpecProperty property) {
        final Name own = property.getter().getAnnotation(Name.class);
        if (own != null) {
            return own.value();
        }
        final Class<?> type = property.type();
        if (Specs.isConfigSpec(type)) {
            final Name section = type.getAnnotation(Name.class);
            if (section != null) {
                return section.value();
            }
        }
        return SettingLabels.of(property.key());
    }

    /**
     * {@code @Protected} only makes sense on a property whose element type is itself a {@code @ConfigSpec}.
     */
    private static void refuseProtectedOutsideAListOfSettings(
            final @Nullable Protected annotation, final String propertyKey) {
        if (annotation != null) {
            throw new IllegalArgumentException("Property '" + propertyKey + "' carries @Protected, but"
                    + " it is not a list of nested settings - @Protected only makes sense there, since"
                    + " it names one of the element's own fields.");
        }
    }

    /**
     * Builds the {@link SchemaNode.ProtectedEntry} a list property's {@code @Protected} describes, or {@code null}.
     *
     * @throws IllegalArgumentException if {@link Protected#field()} names a field the element type
     *                                  does not have - a typo here would otherwise silently protect
     *                                  nothing, which is worse than refusing to build the schema
     */
    private static SchemaNode.@Nullable ProtectedEntry protectedEntryOf(
            final @Nullable Protected annotation, final Class<?> elementType, final String propertyKey) {
        if (annotation == null) {
            return null;
        }
        if (!Specs.from(elementType).properties().containsKey(annotation.field())) {
            throw new IllegalArgumentException("Property '" + propertyKey + "' has @Protected(field = \""
                    + annotation.field() + "\"), but " + elementType.getSimpleName() + " has no such"
                    + " field - @Protected must name one of its real properties.");
        }
        return new SchemaNode.ProtectedEntry(annotation.field(), annotation.value());
    }

    private static boolean isCollection(final Class<?> type) {
        return Collection.class.isAssignableFrom(type) || type.isArray();
    }

    /**
     * The element type of a list-shaped property: the type its entries share, or {@code String} when there is none.
     */
    private static Class<?> collectionElementType(final Method getter) {
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

    private static SettingType scalarTypeOf(final Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return SettingType.BOOLEAN;
        }
        if (type == byte.class
                || type == Byte.class
                || type == short.class
                || type == Short.class
                || type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class) {
            return SettingType.INTEGER;
        }
        if (type == float.class || type == Float.class || type == double.class || type == Double.class) {
            return SettingType.DECIMAL;
        }
        // String, char, a Java enum, and anything else not covered above.
        return SettingType.STRING;
    }

    private static SchemaNode.@Nullable Choices choicesOf(final Method getter, final Class<?> type) {
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
