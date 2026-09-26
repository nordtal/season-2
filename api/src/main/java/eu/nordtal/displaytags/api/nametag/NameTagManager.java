package eu.nordtal.displaytags.api.nametag;

import java.util.Collection;
import org.bukkit.entity.Player;

public interface NameTagManager {
    PlayerNameTag createNameTag(Player player);

    PlayerNameTag getByPlayer(Player player);

    void removeNameTag(Player player);

    Collection<PlayerNameTag> getAll();
}
