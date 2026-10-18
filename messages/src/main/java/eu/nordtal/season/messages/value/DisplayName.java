package eu.nordtal.season.messages.value;

import eu.nordtal.season.common.id.PlayerId;
import java.util.Objects;

/** A player's name as a message shows it.
 * Minecraft shows it with the player's hover card, other targets as plain text.
 *
 * @param player the player, which the hover card and {@code self} are read from
 */
public record DisplayName(PlayerId player, String name) {

    public DisplayName {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
    }
}
