package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.hungergames.game.HungerGamesManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Holds players in place during the countdown, so nobody creeps toward the chests early.
 *
 * Cancelling moves keeps everyone visible on their tower, which spectator mode would not; looking around stays free.
 */
public final class FreezeListener implements Listener {

    private final HungerGamesManager manager;

    public FreezeListener(final HungerGamesManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onMove(final PlayerMoveEvent event) {
        if (!manager.isFrozen()) {
            return;
        }
        if (event.hasChangedPosition()) {
            event.setTo(event.getFrom());
        }
    }
}
