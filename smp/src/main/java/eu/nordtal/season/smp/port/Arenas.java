package eu.nordtal.season.smp.port;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Tells whether a player is in the duel arena. */
public interface Arenas {

    /** Main thread only. */
    boolean isInArena(Player player);

    /** Returns true when a fighter of the running duel changes a block inside the arena. Main thread only. */
    boolean buildsInArena(Player player, Block block);

    /** Returns true when {@code victim} is an active fighter inside the duel arena taking allowed duel damage. */
    default boolean allowsDuelDamage(final Player victim, final @Nullable Entity damager) {
        return false;
    }

    /** Returns true when a duel is running and {@code block} lies inside its arena. Main thread only. */
    default boolean isActiveArenaBlock(final Block block) {
        return false;
    }
}
