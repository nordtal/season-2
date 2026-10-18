package eu.nordtal.season.messages.document;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declares a document once in the module whose code fills it: its name, its values and its slots.
 * The document itself is the file {@code documents/<namespace>/<name>.json} in the module's resources.
 *
 * @param area the group an admin finds it under on the Translations page
 * @param values the record whose components are the values its texts may name
 */
public record DocumentSpec<V extends Record>(
        String namespace, String name, String area, Shape shape, Class<V> values, List<Slot> slots) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** The values of a document whose texts name none. */
    public record None() {}

    public enum Shape {
        /** A message of Components V2. */
        MESSAGE,
        /** A modal: a text block with its title, then one row per field. */
        MODAL,
        /** A message's content, the lines of its text blocks. */
        PLAIN,
        /** A channel's name, the lines of its text blocks on one line. */
        CHANNEL_NAME
    }

    public DocumentSpec {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(values, "values");
        if (!NAME.matcher(namespace).matches() || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(namespace + "/" + name + " is not in kebab case");
        }
        slots = List.copyOf(slots);
    }

    public static <V extends Record> DocumentSpec<V> message(
            final String namespace, final String name, final String area, final Class<V> values, final Slot... slots) {
        return new DocumentSpec<>(namespace, name, area, Shape.MESSAGE, values, List.of(slots));
    }

    public static DocumentSpec<None> message(
            final String namespace, final String name, final String area, final Slot... slots) {
        return message(namespace, name, area, None.class, slots);
    }

    public static <V extends Record> DocumentSpec<V> modal(
            final String namespace, final String name, final String area, final Class<V> values, final Slot... fields) {
        return new DocumentSpec<>(namespace, name, area, Shape.MODAL, values, List.of(fields));
    }

    public static <V extends Record> DocumentSpec<V> plain(
            final String namespace, final String name, final String area, final Class<V> values) {
        return new DocumentSpec<>(namespace, name, area, Shape.PLAIN, values, List.of());
    }

    public static <V extends Record> DocumentSpec<V> channelName(
            final String namespace, final String name, final String area, final Class<V> values) {
        return new DocumentSpec<>(namespace, name, area, Shape.CHANNEL_NAME, values, List.of());
    }

    public String id() {
        return namespace + "/" + name;
    }

    public String resource() {
        return Documents.resource(namespace, name);
    }

    public DocumentDeclaration declaration() {
        return new DocumentDeclaration(namespace, name, area, shape, Value.of(values), slots);
    }
}
