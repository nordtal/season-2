package eu.nordtal.s2.smp.port;

import org.bukkit.entity.Player;

/** Whether a player stands in a duel arena, where a death is the duel's business and nobody else's. */
public interface Arenas {

    /** Main thread only. */
    boolean isInArena(Player player);
}
