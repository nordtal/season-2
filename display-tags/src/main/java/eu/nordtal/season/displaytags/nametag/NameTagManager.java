package eu.nordtal.season.displaytags.nametag;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The name tag of every player that DisplayTags draws one for, and of the other entities given one. */
public interface NameTagManager {
    PlayerNameTag createNameTag(Player player);

    @Nullable
    PlayerNameTag getByPlayer(Player player);

    void removeNameTag(Player player);

    Collection<PlayerNameTag> getAll();

    /** Draws a name tag above {@code entity} whose text {@code text} renders for each viewer. */
    EntityNameTag createEntityNameTag(Entity entity, Function<UUID, Component> text);

    void removeEntityNameTag(Entity entity);

    Collection<EntityNameTag> getAllEntityNameTags();
}
