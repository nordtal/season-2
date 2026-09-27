package eu.nordtal.s2.smp.duel;

import eu.nordtal.s2.smp.config.DuelPlatformSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.region.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Stepping onto a platform, and the three ways a duel ends.
 *
 * Logging out counts as losing, or it becomes a free escape from losing.
 */
public final class DuelListener implements Listener {

    private final List<Map.Entry<Box, DuelType>> platforms = new ArrayList<>();

    private final Duels duels;
    private final org.bukkit.plugin.Plugin plugin;

    public DuelListener(final org.bukkit.plugin.Plugin plugin, final SmpSpec config, final Duels duels) {
        this.plugin = plugin;
        this.duels = duels;
        for (final DuelPlatformSpec spec : config.duelPlatforms()) {
            final Optional<DuelType> type = DuelType.parse(spec.type());
            if (type.isEmpty()) {
                continue;
            }
            platforms.add(Map.entry(
                    new Box(spec.world(), spec.minX(), spec.minY(), spec.minZ(), spec.maxX(), spec.maxY(), spec.maxZ()),
                    type.get()));
        }
    }

    /**
     * A teleport is a move, and Bukkit does not think so.
     *
     * PlayerTeleportEvent has its own handler list, and every travel path on this server is a teleport.
     */
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

        final Player player = event.getPlayer();
        final Optional<DuelType> on = platformAt(to);
        if (on.isEmpty()) {
            duels.steppedOff(player);
            return;
        }
        duels.steppedOn(player, on.get());
    }

    /**
     * A death inside the arena ends the duel.
     *
     * Drops and the death message are cleared: the loadout was the arena's, and a duel is not news.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        if (!duels.isInArena(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        // On the next tick: GraveListener checks the arena at HIGH, this runs at LOWEST.
        Bukkit.getScheduler().runTask(plugin, () -> duels.decide(player));
    }

    /**
     * The blow that would have killed a fighter ends the duel instead, so a duel never shows a death screen.
     *
     * The death path still runs for /kill, the void and setHealth(0), which fire no damage event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(final org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof final Player player) || !duels.isInArena(player)) {
            return;
        }
        if (event.getFinalDamage() < player.getHealth()) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> duels.decide(player));
    }

    /** The other half of a duel death: see {@link Duels#respawned}. */
    @EventHandler
    public void onRespawn(final org.bukkit.event.player.PlayerRespawnEvent event) {
        duels.respawned(event);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        duels.steppedOff(event.getPlayer());
        if (duels.isInArena(event.getPlayer())) {
            duels.decide(event.getPlayer());
        }
    }

    private Optional<DuelType> platformAt(final Location at) {
        for (final Map.Entry<Box, DuelType> platform : platforms) {
            if (platform.getKey().contains(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                return Optional.of(platform.getValue());
            }
        }
        return Optional.empty();
    }
}
