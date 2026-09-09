package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.hungergames.game.HungerGamesManager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Holds players in place during the countdown, so nobody creeps toward the chests early.
 *
 * <p>Position changes are cancelled rather than using spectator mode, which would also hide players
 * from each other - everyone should be visible on their tower. Look direction stays free, because a
 * head turn fires no cancellable position change.</p>
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
