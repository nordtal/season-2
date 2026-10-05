package eu.nordtal.season.messages.value;

import eu.nordtal.season.common.id.PlayerId;
import java.util.Objects;

/**
 * A player's name as a message shows it: in Minecraft with the player's hover card, as plain text elsewhere.
 *
 * @param player whose name it is, which a hover card and {@code self} are read by
 * @param name   the Minecraft name
 */
public record DisplayName(PlayerId player, String name) {

    public DisplayName {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
    }
}
