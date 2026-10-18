package eu.nordtal.season.smp.port;

import org.bukkit.entity.Player;

/** Tells whether a player is in a duel arena. */
public interface Arenas {

    /** Main thread only. */
    boolean isInArena(Player player);
}
