package eu.nordtal.s2.messagerendering;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.value.DisplayName;
import org.jspecify.annotations.Nullable;

/**
 * The card a player's name shows on hover wherever a message draws it; the style {@code plain} draws none.
 * A process hands in what it holds of the players it names, and a player it holds nothing of has no card.
 */
@FunctionalInterface
public interface NameCards {

    /** No card at all, for a process that holds nothing of the players it names. */
    NameCards NONE = name -> null;

    /** Returns the card of {@code name}, or {@code null} where nothing is known of the player. */
    @Nullable
    MessageRef card(DisplayName name);
}
