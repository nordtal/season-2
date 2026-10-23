package eu.nordtal.season.messages.document;

import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.context.Contexts;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.value.Kind;
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
 * @param takes the keys a message value, or each item of a list of messages, may take, else {@code null}
 * @param exampleValue the example read as its kind, as {@code {kind, value}}, where Steward's editor shows it, else
 *     {@code null}, which keeps it out of the declaration's hash
 */
public record Value(
        String kind,
        String example,
        @Nullable List<String> takes,
        @Nullable Object exampleValue) {

    public Value(final String kind, final String example, final @Nullable List<String> takes) {
        this(kind, example, takes, null);
    }

    /** Returns the example as a value of its kind, or null where it reads as none. */
    public @Nullable Object typedExample() {
        return Kind.byToken(kind).orElse(Kind.TEXT).typedExample(example, takes != null);
    }

    /**
     * Returns each declared value's example by name, what a preview fills in, without one that reads as none.
     * A nested message, alone or in a list, carries its own declared examples, as {@link Messages#example} fills them.
     */
    public static Map<String, Object> examples(final Map<String, Value> declared, final Messages messages) {
        final Map<String, Object> examples = new LinkedHashMap<>();
        declared.forEach((name, value) -> {
            final Object typed = value.typedExample();
            if (typed != null) {
                examples.put(name, filled(typed, messages));
            }
        });
        return examples;
    }

    private static Object filled(final Object example, final Messages messages) {
        return switch (example) {
            case MessageRef message -> messages.example(message.key(), Map.of());
            case List<?> items ->
                items.stream().map(item -> filled(item, messages)).toList();
            default -> example;
        };
    }

    /** Returns each value with its example read as its kind, as Steward's editor shows it. */
    public static Map<String, Value> withExampleValues(final Map<String, Value> declared) {
        final Map<String, Value> values = new LinkedHashMap<>();
        declared.forEach((name, value) -> {
            final Object typed = value.typedExample();
            values.put(
                    name,
                    new Value(value.kind, value.example, value.takes, typed == null ? null : MessageJson.typed(typed)));
        });
        return values;
    }

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
