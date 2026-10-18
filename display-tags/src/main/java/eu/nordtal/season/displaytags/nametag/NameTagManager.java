package eu.nordtal.season.displaytags.nametag;

import java.util.Collection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The name tag of every player that DisplayTags draws one for. */
public interface NameTagManager {
    PlayerNameTag createNameTag(Player player);

    @Nullable
    PlayerNameTag getByPlayer(Player player);

    void removeNameTag(Player player);

    Collection<PlayerNameTag> getAll();
}
