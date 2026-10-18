package eu.nordtal.season.messagerendering;

import eu.nordtal.season.messages.value.DisplayName;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/**
 * Draws a player's name in a message. The style {@code plain} bypasses it and shows the bare name.
 */
@FunctionalInterface
public interface Names {

    Names BARE = (name, reader) -> Component.text(name.name());

    Component draw(DisplayName name, Locale reader);
}
