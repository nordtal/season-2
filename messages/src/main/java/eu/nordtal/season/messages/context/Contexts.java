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
 * The registry of context types.
 * Each component of a record is an attribute. Its Java type decides its kind, and it has a static
 * example. A component that is itself a context is expanded one level: {@code winner.team.name}.
 */
public final class Contexts {

    /** The roles of every message with their types. */
    public static final Map<String, Class<? extends MessageContext>> GLOBALS = Map.of(
            "server", ServiceContext.class,
            "season", SeasonContext.class,
            "network", NetworkContext.class,
            "viewer", PlayerContext.class);

    /** The order in which Steward lists {@link #GLOBALS}. */
    public static final List<String> GLOBAL_ROLES = List.of("server", "season", "network", "viewer");

    public static final String SELF = "self";

    private static final Map<Class<?>, List<Attribute>> ATTRIBUTES = new ConcurrentHashMap<>();

    private Contexts() {}

    /**
     * One attribute of a context type.
     *
     * @param name    the name, with dots for the attribute of a nested context
     * @param example the value that an editor shows
     */
    public record Attribute(String name, Kind kind, String example) {}

    public static boolean isContext(final Class<?> type) {
        return type.isRecord()
                && MessageContext.class.isAssignableFrom(type)
                && type.isAnnotationPresent(ContextType.class);
    }

    public static String type(final Class<?> type) {
        final ContextType annotation = type.getAnnotation(ContextType.class);
        if (annotation == null) {
            throw new IllegalArgumentException(type.getName() + " has no @ContextType");
        }
        return annotation.value();
    }

    public static String name(final Class<?> type) {
        return type.getAnnotation(ContextType.class).name();
    }

    /**
     * Returns the attributes of the type in component order, with a nested context expanded.
     *
     * @throws IllegalStateException if a component has no kind or nests a context two levels deep
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
     * Returns {@code values} with each context replaced by its {@code role.attribute} entries.
     * The result adds the environment's global roles that {@code values} lacks, and {@code viewer}.
     *
     * @param reader the reading player, or {@code null} for a console or an unknown reader
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
