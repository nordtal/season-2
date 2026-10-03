package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messages.value.DisplayName;
import eu.nordtal.s2.messages.value.Example;

/**
 * A player on the network.
 * Every player role also has {@code self}, true for the one reading, so a broadcast can say "you" to its subject.
 *
 * @param name its name, with the hover card in Minecraft
 */
@ContextType(value = "player", name = "Player")
public record PlayerContext(@Example("Alex") DisplayName name) implements MessageContext {

    /** Returns the player with the account {@code player}, called {@code name}. */
    public static PlayerContext of(final PlayerId player, final String name) {
        return new PlayerContext(new DisplayName(player, name));
    }

    /** Returns the account this player plays on. */
    public PlayerId player() {
        return name.player();
    }
}
