package eu.nordtal.season.messages.document;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Declares a document once in the module whose code fills it: its name, its values and its slots.
 * The document itself is the file {@code documents/<namespace>/<name>.json} in the module's resources.
 *
 * @param title what an admin reads as its name, unique in its area; {@link #titled} gives it
 * @param area the group an admin finds it under on the Translations page
 * @param rows a menu's chest rows, 0 for every other shape; a menu whose layout varies declares one example
 * @param values the record whose components are the values its texts may name
 */
public record DocumentSpec<V extends Record>(
        String namespace,
        String name,
        @Nullable String title,
        String area,
        Shape shape,
        int rows,
        Class<V> values,
        List<Slot> slots) {

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
        CHANNEL_NAME,
        /** A game menu: a text block with its window title, then its items and labels. */
        MENU,
        /** Game chat: the lines of its text blocks, one chat line each. */
        CHAT,
        /** A tab list's header or footer: the lines of its text blocks. */
        TAB_LIST,
        /** A screen that replaces the game, such as a kick: the lines of its text blocks. */
        KICK_SCREEN,
        /** A title over the screen: its first line is the title, every line after it the subtitle. */
        TITLE,
        /** A boss bar line's text, which the HUD paints in its own font: its shown lines as plain text on one line. */
        BOSS_BAR,
        /** The server list's description under the server's name: the lines of its text blocks. */
        SERVER_LIST,
        /** The line over the hotbar: its shown lines on one line. */
        ACTION_BAR,
        /** A text floating in the world, a text display's or an entity's name: the lines of its text blocks. */
        HOLOGRAM;

        /** Returns whether the game draws it, with the game's renderer, rather than Discord. */
        public boolean game() {
            return this == MENU
                    || this == CHAT
                    || this == TAB_LIST
                    || this == KICK_SCREEN
                    || this == TITLE
                    || this == BOSS_BAR
                    || this == SERVER_LIST
                    || this == ACTION_BAR
                    || this == HOLOGRAM;
        }
    }

    public DocumentSpec {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(values, "values");
        if (!NAME.matcher(namespace).matches() || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(namespace + "/" + name + " is not in kebab case");
        }
        slots = List.copyOf(slots);
        for (final Slot slot : slots) {
            if (shape == Shape.MENU
                    && slot.type() == Slot.Type.ITEM
                    && slot.at().isEmpty()) {
                throw new IllegalArgumentException(
                        namespace + "/" + name + " places its item " + slot.id() + " in no slot");
            }
        }
    }

    public static <V extends Record> DocumentSpec<V> message(
            final String namespace, final String name, final String area, final Class<V> values, final Slot... slots) {
        return new DocumentSpec<>(namespace, name, null, area, Shape.MESSAGE, 0, values, List.of(slots));
    }

    public static DocumentSpec<None> message(
            final String namespace, final String name, final String area, final Slot... slots) {
        return message(namespace, name, area, None.class, slots);
    }

    public static <V extends Record> DocumentSpec<V> modal(
            final String namespace, final String name, final String area, final Class<V> values, final Slot... fields) {
        return new DocumentSpec<>(namespace, name, null, area, Shape.MODAL, 0, values, List.of(fields));
    }

    public static <V extends Record> DocumentSpec<V> plain(
            final String namespace, final String name, final String area, final Class<V> values) {
        return new DocumentSpec<>(namespace, name, null, area, Shape.PLAIN, 0, values, List.of());
    }

    public static <V extends Record> DocumentSpec<V> channelName(
            final String namespace, final String name, final String area, final Class<V> values) {
        return new DocumentSpec<>(namespace, name, null, area, Shape.CHANNEL_NAME, 0, values, List.of());
    }

    public static <V extends Record> DocumentSpec<V> menu(
            final String namespace,
            final String name,
            final String area,
            final Class<V> values,
            final int rows,
            final Slot... slots) {
        return new DocumentSpec<>(namespace, name, null, area, Shape.MENU, rows, values, List.of(slots));
    }

    /** Declares a game text of {@code shape} that holds only text blocks of lines. */
    public static <V extends Record> DocumentSpec<V> game(
            final String namespace, final String name, final String area, final Shape shape, final Class<V> values) {
        if (!shape.game() || shape == Shape.MENU) {
            throw new IllegalArgumentException(shape + " is no game text");
        }
        return new DocumentSpec<>(namespace, name, null, area, shape, 0, values, List.of());
    }

    public static DocumentSpec<None> game(
            final String namespace, final String name, final String area, final Shape shape) {
        return game(namespace, name, area, shape, None.class);
    }

    /** Returns this declaration with the name an admin reads, a short noun phrase such as "Access paid". */
    public DocumentSpec<V> titled(final String title) {
        return new DocumentSpec<>(namespace, name, title, area, shape, rows, values, slots);
    }

    /** Returns this document filled with {@code values}, as the code sends it. */
    public Sent<V> with(final V values) {
        return new Sent<>(this, values);
    }

    public String id() {
        return namespace + "/" + name;
    }

    public String resource() {
        return Documents.resource(namespace, name);
    }

    public DocumentDeclaration declaration() {
        return new DocumentDeclaration(
                namespace,
                name,
                Objects.requireNonNull(title, () -> id() + " has no name"),
                area,
                shape,
                rows,
                Value.of(values),
                slots);
    }
}
