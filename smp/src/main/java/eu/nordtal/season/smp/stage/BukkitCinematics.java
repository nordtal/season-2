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
 * A quit or a death cancels the staging of that player.
 * A quit clears the effect before the server saves the player.
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
     * Starts {@code cinematic} for {@code player}. Main thread only.
     *
     * @return {@code false} if the player already has a staging
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

    /** Cancels every staging at plugin disable. */
    public void stop() {
        cinematics.cancelAll();
    }
}
