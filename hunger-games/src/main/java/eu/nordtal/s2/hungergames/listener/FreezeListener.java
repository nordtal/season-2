package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.hungergames.game.HungerGamesManager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Holds players in place during the countdown - docs/hunger-games.md#start: "they are held in
 * place for the countdown - nobody creeps toward the chests early."
 * <p>
 * Implemented as plain {@link PlayerMoveEvent} cancellation on any actual position change, rather
 * than {@code Player#setInvulnerable} (which stops damage, not movement) or a spectator-mode trick
 * (which would also hide the player from others, which docs/hunger-games.md#start does not ask
 * for - everyone should be visible, standing on their tower, during the countdown). Cancelling the
 * move event leaves look direction free (head turns do not fire a cancollable position change) so
 * players can still look around while frozen.
 * </p>
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
