package eu.nordtal.season.messages.context;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Kind;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The registry of context types: what each record offers as attributes, and how a message's contexts become values.
 * Each component of a type's record is an attribute with a kind, which its Java type decides, and a static example;
 * a component that is itself a context is expanded one level deep, as {@code winner.team.name}.
 */
public final class Contexts {

    /** The roles every message has, with their types, in the order the Steward UI lists them. */
    public static final Map<String, Class<? extends MessageContext>> GLOBALS = Map.of(
            "server", ServiceContext.class,
            "season", SeasonContext.class,
            "network", NetworkContext.class,
            "viewer", PlayerContext.class);

    /** The order of {@link #GLOBALS}, which {@code Map.of} does not keep. */
    public static final List<String> GLOBAL_ROLES = List.of("server", "season", "network", "viewer");

    /** The attribute every player role has besides its components. */
    public static final String SELF = "self";

    private static final Map<Class<?>, List<Attribute>> ATTRIBUTES = new ConcurrentHashMap<>();

    private Contexts() {}

    /**
     * One attribute of a context type.
     *
     * @param name    its name, dotted for a nested context's attribute
     * @param kind    what it shows
     * @param example what an editor shows for it
     */
    public record Attribute(String name, Kind kind, String example) {}

    /** Returns whether {@code type} is a context record. */
    public static boolean isContext(final Class<?> type) {
        return type.isRecord()
                && MessageContext.class.isAssignableFrom(type)
                && type.isAnnotationPresent(ContextType.class);
    }

    /** Returns the type's key, e.g. {@code player}. */
    public static String type(final Class<?> type) {
        final ContextType annotation = type.getAnnotation(ContextType.class);
        if (annotation == null) {
            throw new IllegalArgumentException(type.getName() + " has no @ContextType");
        }
        return annotation.value();
    }

    /** Returns the name an admin reads for the type. */
    public static String name(final Class<?> type) {
        return type.getAnnotation(ContextType.class).name();
    }

    /**
     * Returns the type's attributes, in component order, a nested context's expanded in place.
     *
     * @throws IllegalStateException when a component has no kind, or nests a context two levels deep
     */
    public static List<Attribute> attributes(final Class<?> type) {
        return ATTRIBUTES.computeIfAbsent(type, t -> List.copyOf(collect(t, "", 0)));
    }

    private static List<Attribute> collect(final Class<?> type, final String prefix, final int depth) {
        if (!isContext(type)) {
            throw new IllegalArgumentException(type.getName() + " is not a context record");
        }
        final List<Attribute> attributes = new ArrayList<>();
        for (final RecordComponent component : type.getRecordComponents()) {
            final Class<?> value = component.getType();
            if (isContext(value)) {
                if (depth > 0) {
                    throw new IllegalStateException(type.getName() + "." + component.getName()
                            + " nests a context two levels deep; one level is all a text can name");
                }
                attributes.addAll(collect(value, prefix + component.getName() + ".", depth + 1));
                continue;
            }
            final Kind kind = Kind.of(value)
                    .orElseThrow(() -> new IllegalStateException(type.getName() + "." + component.getName() + " is a "
                            + value.getSimpleName() + ", which is no kind a message can show"));
            final Example example = component.getAnnotation(Example.class);
            attributes.add(new Attribute(
                    prefix + component.getName(), kind, example == null ? kind.defaultExample() : example.value()));
        }
        if (type == PlayerContext.class) {
            attributes.add(new Attribute(prefix + SELF, Kind.CHOICE, "false"));
        }
        return attributes;
    }

    /**
     * Returns {@code values} with each context replaced by one {@code role.attribute} entry per attribute.
     * The environment's global roles join where the message did not name them, and the reader as {@code viewer}.
     *
     * @param reader the player reading, or {@code null} for a console or a reader not yet known
     */
    public static Map<String, Object> flatten(
            final Map<String, ?> values, final MessageEnvironment environment, final @Nullable PlayerContext reader) {
        final Map<String, Object> flat = new LinkedHashMap<>();
        final PlayerId self = reader == null ? null : reader.player();
        values.forEach((role, value) -> {
            if (value instanceof final MessageContext context) {
                expand(flat, role + ".", context, self, 0);
            } else if (value != null) {
                flat.put(role, value);
            }
        });
        environment.globals().forEach((role, context) -> {
            if (!values.containsKey(role)) {
                expand(flat, role + ".", context, self, 0);
            }
        });
        if (reader != null && !values.containsKey("viewer")) {
            expand(flat, "viewer.", reader, self, 0);
        }
        return flat;
    }

    private static void expand(
            final Map<String, Object> into,
            final String prefix,
            final MessageContext context,
            final @Nullable PlayerId reader,
            final int depth) {
        for (final RecordComponent component : context.getClass().getRecordComponents()) {
            final Object value;
            try {
                value = component.getAccessor().invoke(context);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read " + component + " of " + context, e);
            }
            if (value instanceof final MessageContext inner && depth == 0) {
                expand(into, prefix + component.getName() + ".", inner, reader, depth + 1);
            } else if (value != null) {
                into.put(prefix + component.getName(), value);
            }
        }
        if (context instanceof final PlayerContext player) {
            into.put(prefix + SELF, player.player().equals(reader));
        }
    }
}
