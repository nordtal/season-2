package eu.nordtal.s2.papercommon.stage;

import eu.nordtal.s2.common.stage.Cinematic;
import eu.nordtal.s2.common.stage.Cinematics;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The Paper half of the staging device: a scheduler, a screen, and the two events that end one.
 *
 * <h2>What is here and what is not</h2>
 * Everything about <em>when</em> a frame appears, what a second staging does to a first, and what a
 * cancel has to undo lives in {@link Cinematics}, in {@code :common}, where it can be driven by a
 * fake clock. What is here is the three things that need a Paper type: turning a delay into a
 * {@code BukkitTask}, turning a frame into a title, and noticing that the player is gone.
 *
 * <h2>Both endings are cancellations, and neither is a failure</h2>
 * <ul>
 *   <li><b>Quitting.</b> {@link PlayerQuitEvent} fires while the player is still resolvable, which
 *       is what lets {@link PlayerStage#clear()} take the effect off before they are written to
 *       disk. Without it somebody who logs out three seconds into the welcome comes back blind, to
 *       a server that has forgotten why.</li>
 *   <li><b>Dying.</b> A staging is about a moment, and being killed during it is a different moment.
 *       More concretely: the respawn screen sits under the title, and blindness survives a death by
 *       default - so the frames would go on playing over somebody's death screen.</li>
 * </ul>
 *
 * <p>{@link EventPriority#MONITOR} on both: neither changes the event, and cancelling a staging must
 * not depend on whether something earlier in the chain cancelled something else.
 *
 * <h2>It is designed to be reused, and the reuse is not built</h2>
 * The owner has named a milestone being finished, the start event opening, a hunger games winner
 * and a prestige level as later users of this. Each of those is a {@link Cinematic} and a call to
 * {@link #start} from wherever that moment is decided; nothing in this class or in
 * {@code :common}'s half has to change for any of them. What deliberately is <em>not</em> here is a
 * registry of named stagings or a config file describing them - a moment's frames belong to the
 * module that owns the moment, and a shared list of them would be the second place a font key can
 * be wrong.
 */
public final class BukkitCinematics implements Listener {

    private final Plugin plugin;
    private final FeedbackPlayer sounds;
    private final Cinematics cinematics;

    public BukkitCinematics(final Plugin plugin, final FeedbackPlayer sounds) {
        this.plugin = plugin;
        this.sounds = sounds;
        this.cinematics = new Cinematics((task, delayTicks) -> {
            final BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return scheduled::cancel;
        });
    }

    /**
     * Starts one for this player. Main thread.
     *
     * @return whether it started. {@code false} means this player is already in the middle of one -
     * see {@link Cinematics#start}, which refuses rather than queues
     */
    public boolean start(final Player player, final Cinematic cinematic) {
        return cinematics.start(player.getUniqueId(), cinematic,
                new PlayerStage(plugin, player.getUniqueId(), sounds));
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
     * Stops everything - what a plugin calls at disable.
     *
     * <p>Paper disables plugins <b>before</b> it saves and disconnects players (measured on a real
     * 26.2 shutdown on 2026-09-06, and the reason {@code SmpPlugin} pays out spins in flight), so a
     * staging still running at that point would be written to disk with its effect on. This is the
     * one call that takes it off while there is still somebody to take it off.
     */
    public void stop() {
        cinematics.cancelAll();
    }
}
