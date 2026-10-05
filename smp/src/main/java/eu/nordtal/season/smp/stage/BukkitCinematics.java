package eu.nordtal.season.smp.stage;

import eu.nordtal.season.papercommon.time.PaperScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * The Paper half of the staging device; timing lives in {@link Cinematics}.
 *
 * A quit or a death cancels it, and a quit clears the effect before the player is saved.
 */
public final class BukkitCinematics implements Listener {

    private final Plugin plugin;
    private final FeedbackPlayer sounds;
    private final Cinematics cinematics;

    public BukkitCinematics(final Plugin plugin, final FeedbackPlayer sounds) {
        this.plugin = plugin;
        this.sounds = sounds;
        this.cinematics = new Cinematics(PaperScheduler.of(plugin).mainThread());
    }

    /**
     * Starts one for this player, on the main thread.
     *
     * @return whether it started; {@code false} if this player is already in one
     */
    public boolean start(final Player player, final Cinematic cinematic) {
        return cinematics.start(player.getUniqueId(), cinematic, new PlayerStage(plugin, player.getUniqueId(), sounds));
    }

    public boolean isRunning(final Player player) {
        return cinematics.isRunning(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        cinematics.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        cinematics.cancel(event.getEntity().getUniqueId());
    }

    /**
     * Stops everything, at plugin disable.
     *
     * Paper disables plugins before it saves players, so this is the last chance to clear the effect.
     */
    public void stop() {
        cinematics.cancelAll();
    }
}
