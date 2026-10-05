package eu.nordtal.season.spec.schema;

import eu.nordtal.season.spec.SpecProperty;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.Comment;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Protected;
import eu.nordtal.season.spec.annotation.Secret;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds the schema of a {@code @ConfigSpec} interface: each setting's name, explanation, type, choices and flags.
 *
 * A setting's group is the nesting of the tree, and the spec's header is the root's {@code explanation}.
 */
public final class SchemaWriter {

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
     * Returns the spec's {@code @ConfigSpec(header)} as one text, or {@code ""} when it declares none.
     *
     * @param specType the spec interface
     * @return the header lines joined with {@code '\n'}, verbatim and with blank ones kept, or {@code ""}
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
     *                                  does not have, since a typo would otherwise protect nothing
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
