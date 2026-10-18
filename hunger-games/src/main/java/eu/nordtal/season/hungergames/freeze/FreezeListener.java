package eu.nordtal.season.hungergames.freeze;

import eu.nordtal.season.hungergames.game.HungerGamesManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Cancels the movement of players during the countdown. Players can still look around. */
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
