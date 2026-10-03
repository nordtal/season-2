package eu.nordtal.s2.steward;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messages.MessageRef;
import java.io.IOException;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Writes the TypeScript types of steward's API from the Java records its routes answer and read.
 *
 * Run by {@code :steward:generateApiTypes}; {@code ApiTypesTest} fails while the committed file differs.
 */
public final class ApiTypes {

    /** Where the frontend reads them, relative to the steward module. */
    static final Path TARGET = Path.of("frontend/src/lib/api.gen.ts");

    private static final int WIDTH = 120;

    private final Map<Class<?>, String> declared = new LinkedHashMap<>();
    private final Map<String, Class<?>> names = new LinkedHashMap<>();
    private final List<Class<?>> pending = new ArrayList<>();

    private ApiTypes() {}

    /** The whole file, one type per record or enum reachable from {@link ApiRoots#ROOTS}, in the order reached. */
    static String render() {
        final ApiTypes types = new ApiTypes();
        ApiRoots.ROOTS.forEach(types::reach);
        final StringBuilder out = new StringBuilder();
        out.append(
                "/** Steward's API as its Java records declare it; written by `./gradlew :steward:generateApiTypes`. */\n");
        out.append("import type { MessageRef } from \"@/lib/texts\"\n");
        ApiRoots.ALIASES.forEach((name, type) -> out.append("\nexport type ")
                .append(name)
                .append(" = ")
                .append(types.aliased(type))
                .append('\n'));
        for (int at = 0; at < types.pending.size(); at++) {
            out.append('\n').append(types.declare(types.pending.get(at)));
        }
        return out.toString();
    }

    /** Writes the file into the frontend, the one build step behind it. */
    public static void main(final String[] args) throws IOException {
        Files.writeString(TARGET, render(), StandardCharsets.UTF_8);
        TextTypes.main(args);
    }

    private void reach(final Class<?> type) {
        if (declared.containsKey(type)) {
            return;
        }
        final String name = ApiRoots.NAMES.getOrDefault(type, type.getSimpleName());
        final Class<?> other = names.putIfAbsent(name, type);
        if (other != null) {
            throw new IllegalStateException("two API types are called " + name + ": " + other + " and " + type);
        }
        declared.put(type, name);
        pending.add(type);
    }

    /** A type written out under a name of its own, for a map the routes build without a record. */
    private String aliased(final java.lang.reflect.Type type) {
        if (type instanceof Class<?> plain) {
            return named(plain);
        }
        if (type instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) {
            final java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                return aliased(arguments[0]) + "[]";
            }
            if (Map.class.isAssignableFrom(raw)) {
                final String key =
                        arguments[0] instanceof Class<?> enumKey && enumKey.isEnum() ? named(enumKey) : "string";
                return "Record<" + key + ", " + aliased(arguments[1]) + ">";
            }
        }
        throw new IllegalStateException("no wire shape for " + type);
    }

    private String declare(final Class<?> type) {
        if (type.isEnum()) {
            final String name = declared.get(type);
            final String union = union(type, ("export type " + name + " = ").length());
            return "export type " + name + " =" + (union.startsWith("\n") ? union : " " + union) + "\n";
        }
        if (!type.isRecord()) {
            throw new IllegalStateException(type + " is neither a record nor an enum, so it has no wire shape");
        }
        final StringBuilder out = new StringBuilder("export type " + declared.get(type) + " = {\n");
        for (final RecordComponent component : type.getRecordComponents()) {
            final AnnotatedType annotated = component.getAnnotatedType();
            final boolean optional = annotated.isAnnotationPresent(Nullable.class);
            out.append("  ")
                    .append(wireName(component))
                    .append(optional ? "?: " : ": ")
                    .append(plainTypeOf(annotated))
                    .append('\n');
        }
        return out.append("}\n").toString();
    }

    private static String wireName(final RecordComponent component) {
        final SerializedName renamed = component.getAccessor().getAnnotation(SerializedName.class);
        if (renamed != null) {
            return renamed.value();
        }
        final SerializedName onField = fieldAnnotation(component);
        return onField == null ? component.getName() : onField.value();
    }

    private static @Nullable SerializedName fieldAnnotation(final RecordComponent component) {
        try {
            return component
                    .getDeclaringRecord()
                    .getDeclaredField(component.getName())
                    .getAnnotation(SerializedName.class);
        } catch (final NoSuchFieldException impossible) {
            throw new IllegalStateException("a record component without its field", impossible);
        }
    }

    /** A type inside a list or a map, where a null is written out rather than left away. */
    private String typeOf(final AnnotatedType annotated) {
        final String plain = plainTypeOf(annotated);
        return annotated.isAnnotationPresent(Nullable.class) ? plain + " | null" : plain;
    }

    private String plainTypeOf(final AnnotatedType annotated) {
        if (annotated instanceof AnnotatedArrayType array) {
            return elementOf(array.getAnnotatedGenericComponentType()) + "[]";
        }
        if (annotated instanceof AnnotatedParameterizedType parameterized) {
            final Class<?> raw =
                    (Class<?>) ((java.lang.reflect.ParameterizedType) parameterized.getType()).getRawType();
            final AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                return elementOf(arguments[0]) + "[]";
            }
            if (Map.class.isAssignableFrom(raw)) {
                return "Record<" + keyOf(arguments[0]) + ", " + typeOf(arguments[1]) + ">";
            }
            throw new IllegalStateException("no wire shape for " + parameterized.getType());
        }
        if (!(annotated.getType() instanceof Class<?> type)) {
            throw new IllegalStateException("no wire shape for " + annotated.getType());
        }
        return named(type);
    }

    /** An element of a list, in parentheses when it is a union, so the brackets bind to all of it. */
    private String elementOf(final AnnotatedType element) {
        final String inner = typeOf(element);
        return inner.contains(" | ") ? "(" + inner + ")" : inner;
    }

    private String keyOf(final AnnotatedType key) {
        if (key.getType() instanceof Class<?> type && type.isEnum()) {
            reach(type);
            return declared.get(type);
        }
        return "string";
    }

    private String named(final Class<?> type) {
        if (type == String.class
                || type == char.class
                || type == Character.class
                || type == Instant.class
                || type == Duration.class
                || type == LocalDate.class
                || type == UUID.class
                || type == DiscordId.class
                || type == PlayerId.class) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(type) || type == BigDecimal.class) {
            return "number";
        }
        if (type == Optional.class) {
            throw new IllegalStateException("Optional has no wire shape; a @Nullable component is an absent field");
        }
        if (type == MessageRef.class) {
            return "MessageRef";
        }
        if (type == JsonObject.class) {
            return "Record<string, unknown>";
        }
        if (JsonElement.class.isAssignableFrom(type) || type == Object.class) {
            return "unknown";
        }
        if (type.isRecord() || type.isEnum()) {
            reach(type);
            return declared.get(type);
        }
        throw new IllegalStateException("no wire shape for " + type);
    }

    /** An enum's constants as a union of strings, broken one per line when it would not fit. */
    private static String union(final Class<?> type, final int indent) {
        final List<String> names = new ArrayList<>();
        for (final Object constant : type.getEnumConstants()) {
            names.add('"' + spelled((Enum<?>) constant) + '"');
        }
        final String line = String.join(" | ", names);
        if (indent + line.length() <= WIDTH) {
            return line;
        }
        return names.stream().map(name -> "\n  | " + name).collect(Collectors.joining());
    }

    private static String spelled(final Enum<?> constant) {
        if (WireJson.LOWERCASE.contains(constant.getDeclaringClass())) {
            return WireJson.spelled(constant);
        }
        try {
            final SerializedName renamed =
                    constant.getDeclaringClass().getField(constant.name()).getAnnotation(SerializedName.class);
            return renamed == null ? constant.name() : renamed.value();
        } catch (final NoSuchFieldException impossible) {
            throw new IllegalStateException("an enum constant without its field", impossible);
        }
    }
}
