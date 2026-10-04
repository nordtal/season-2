package eu.nordtal.s2.messagerendering;

import eu.nordtal.s2.messages.value.DisplayName;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/**
 * How a process draws a player's name in a message; the style {@code plain} bypasses it for the bare name.
 * By default it is the bare name; a server draws its own composition (a flag, a colour, a crest), and its card on
 * hover is the {@link NameCards}' business.
 */
@FunctionalInterface
public interface Names {

    /** The bare name, which is what a process without a composition of its own shows. */
    Names BARE = (name, reader) -> Component.text(name.name());

    /** Draws {@code name} for a reader of {@code reader}'s language. */
    Component draw(DisplayName name, Locale reader);
}
