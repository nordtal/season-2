package eu.nordtal.season.messagerendering;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.DisplayName;
import org.jspecify.annotations.Nullable;

/** Supplies the card a player's name shows on hover. The style {@code plain} shows none. */
@FunctionalInterface
public interface NameCards {

    NameCards NONE = name -> null;

    @Nullable
    MessageRef card(DisplayName name);
}
