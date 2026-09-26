package eu.nordtal.s2.common.message.context;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Turns message contexts into placeholders, and holds the global {@code server} and {@code season}. */
public final class Contexts {

    /** The roles every message has, with their types, in the order the Steward UI lists them. */
    public static final Map<String, Class<? extends MessageContext>> GLOBALS =
            Map.of("server", ServiceContext.class, "season", SeasonContext.class);

    /** The order of {@link #GLOBALS}, which {@code Map.of} does not keep. */
    public static final List<String> GLOBAL_ROLES = List.of("server", "season");

    private static final Map<String, MessageContext> GLOBAL_VALUES =
            new ConcurrentHashMap<>(Map.of("season", SeasonContext.CURRENT));

    private static final Map<Class<?>, RecordComponent[]> COMPONENTS = new ConcurrentHashMap<>();

    private Contexts() {}

    /** Sets which service this process is, for {@code {server.name}}; until then the placeholder stays as written. */
    public static void server(final String service) {
        GLOBAL_VALUES.put("server", new ServiceContext(service));
    }

    /** @return whether {@code type} is a context record */
    public static boolean isContext(final Class<?> type) {
        return type.isRecord()
                && MessageContext.class.isAssignableFrom(type)
                && type.isAnnotationPresent(ContextType.class);
    }

    /** @return the type's key, e.g. {@code player} */
    public static String type(final Class<?> type) {
        final ContextType annotation = type.getAnnotation(ContextType.class);
        if (annotation == null) {
            throw new IllegalArgumentException(type.getName() + " has no @ContextType");
        }
        return annotation.value();
    }

    /** @return the name an admin reads for the type */
    public static String name(final Class<?> type) {
        return type.getAnnotation(ContextType.class).name();
    }

    /** @return the type's placeholders, in component order */
    public static List<String> properties(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : components(type)) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * @param values placeholder name to value, as a message spec fills them
     * @return the same values with every context replaced by one {@code role.property} entry per
     *         component, and the global roles added where the message did not name them itself
     */
    public static Map<String, Object> flatten(final Map<String, ?> values) {
        final Map<String, Object> flat = new LinkedHashMap<>();
        values.forEach((role, value) -> {
            if (value instanceof final MessageContext context) {
                expand(flat, role, context);
            } else {
                flat.put(role, value);
            }
        });
        GLOBAL_VALUES.forEach((role, context) -> {
            if (!values.containsKey(role)) {
                expand(flat, role, context);
            }
        });
        return flat;
    }

    private static void expand(final Map<String, Object> into, final String role, final MessageContext context) {
        for (final RecordComponent component : components(context.getClass())) {
            try {
                into.put(
                        role + "." + component.getName(),
                        component.getAccessor().invoke(context));
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read " + component + " of " + context, e);
            }
        }
    }

    private static RecordComponent[] components(final Class<?> type) {
        return COMPONENTS.computeIfAbsent(type, t -> {
            if (!t.isRecord()) {
                throw new IllegalArgumentException(t.getName() + " is not a record");
            }
            return t.getRecordComponents();
        });
    }
}
