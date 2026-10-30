package eu.nordtal.season.smp.port;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Tells whether a player is in the duel arena. */
public interface Arenas {

    /** Main thread only. */
    boolean isInArena(Player player);

    /** Returns true when a fighter of the running duel changes a block inside the arena. Main thread only. */
    boolean buildsInArena(Player player, Block block);
}
