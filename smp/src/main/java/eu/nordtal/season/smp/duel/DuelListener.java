package eu.nordtal.season.smp.duel;

import eu.nordtal.season.papercommon.time.PaperScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** A disconnect counts as a defeat. */
public final class DuelListener implements Listener {

    private final Duels duels;
    private final org.bukkit.plugin.Plugin plugin;

    public DuelListener(final org.bukkit.plugin.Plugin plugin, final Duels duels) {
        this.plugin = plugin;
        this.duels = duels;
    }

    /** {@code PlayerTeleportEvent} has its own handler list. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(final org.bukkit.event.player.PlayerTeleportEvent event) {
        onMove(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX()
                && to.getBlockY() == from.getBlockY()
                && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        duels.moved(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        if (!duels.isInArena(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        // GraveListener checks the arena at HIGH before this handler.
        PaperScheduler.of(plugin).onMain(() -> duels.decide(player));
    }

    /** /kill, the void and {@code setHealth(0)} fire no damage event, so {@code onDeath} ends those duels. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(final org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof final Player player) || !duels.isInArena(player)) {
            return;
        }
        if (event.getFinalDamage() < player.getHealth()) {
            return;
        }
        event.setCancelled(true);
        PaperScheduler.of(plugin).onMain(() -> duels.decide(player));
    }

    @EventHandler
    public void onRespawn(final org.bukkit.event.player.PlayerRespawnEvent event) {
        duels.respawned(event);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        duels.quit(event.getPlayer());
    }
}
