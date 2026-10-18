package eu.nordtal.season.messages.document;

import eu.nordtal.season.messages.context.Contexts;
import eu.nordtal.season.messages.spec.MessageSchema;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A value a document's texts may name.
 *
 * @param kind the kind's token
 * @param example what a preview and the length check fill in
 * @param takes the keys a message value may take, else {@code null}
 */
public record Value(String kind, String example, @Nullable List<String> takes) {

    /** Returns the values of a record's components by name, with a context expanded into its attributes. */
    public static Map<String, Value> of(final Class<? extends Record> type) {
        final Map<String, Value> values = new LinkedHashMap<>();
        for (final RecordComponent component : type.getRecordComponents()) {
            final Class<?> kind = component.getType();
            if (Contexts.isContext(kind)) {
                Contexts.attributes(kind)
                        .forEach(attribute -> values.put(
                                component.getName() + "." + attribute.name(),
                                new Value(attribute.kind().token(), attribute.example(), null)));
                continue;
            }
            final MessageSchema.Arg arg = MessageSchema.arg(type.getName(), component.getName(), kind, component);
            if (arg.kind() == null || arg.example() == null) {
                throw new IllegalStateException(
                        type.getName() + "." + component.getName() + " is no value a text shows");
            }
            values.put(component.getName(), new Value(arg.kind(), arg.example(), arg.takes()));
        }
        return values;
    }

    /** Returns the components of {@code record} by name, as a text fills them. */
    public static Map<String, Object> read(final Record record) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                final Method accessor = component.getAccessor();
                // A module's value records are usually not public.
                accessor.setAccessible(true);
                final Object value = accessor.invoke(record);
                if (value != null) {
                    values.put(component.getName(), value);
                }
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read " + component, e);
            }
        }
        return values;
    }
}
