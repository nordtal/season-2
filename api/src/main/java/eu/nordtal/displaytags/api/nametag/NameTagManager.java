package eu.nordtal.displaytags.api.nametag;

import java.util.Collection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Owns the name tag of every player DisplayTags currently draws one for.
 */
public interface NameTagManager {
    /**
     * Creates a name tag for {@code player}, replacing any tag it already had.
     *
     * @param player the player to create a name tag for
     * @return the new name tag
     */
    PlayerNameTag createNameTag(Player player);

    /**
     * @param player the player to look up
     * @return the player's current name tag, or {@code null} if none is registered
     */
    @Nullable
    PlayerNameTag getByPlayer(Player player);

    /**
     * Removes {@code player}'s name tag, if it has one.
     *
     * Despawns its display for all viewers and hands their vanilla name tag back.
     *
     * @param player the player whose name tag is removed
     */
    void removeNameTag(Player player);

    /**
     * @return every name tag currently registered
     */
    Collection<PlayerNameTag> getAll();
}
