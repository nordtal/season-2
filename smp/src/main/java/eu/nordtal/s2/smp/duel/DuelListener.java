package eu.nordtal.s2.smp.duel;

import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.region.Box;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stepping onto a platform, and the three ways a duel ends.
 *
 * <p>A defeat is a defeat however it arrives: killed, or disconnected. <b>Logging out has to count
 * as losing</b>, or it becomes a free escape from losing - which is the sort of thing one person
 * discovers and everybody else then has to live with.
 */
public final class DuelListener implements Listener {

    /** The configured platforms, resolved once: a box and the loadout it hands out. */
    private final List<Map.Entry<Box, DuelType>> platforms = new ArrayList<>();
    private final Duels duels;
    private final org.bukkit.plugin.Plugin plugin;

    public DuelListener(final org.bukkit.plugin.Plugin plugin, final SmpSpec config,
                        final Duels duels) {
        this.plugin = plugin;
        this.duels = duels;
        for (final SmpSpec.DuelPlatformSpec spec : config.duelPlatforms()) {
            final Optional<DuelType> type = DuelType.parse(spec.type());
            if (type.isEmpty()) {
                continue;
            }
            platforms.add(Map.entry(new Box(spec.world(), spec.minX(), spec.minY(), spec.minZ(),
                    spec.maxX(), spec.maxY(), spec.maxZ()), type.get()));
        }
    }

    /**
     * A teleport is a move, and Bukkit does not think so.
     *
     * <p>{@code PlayerTeleportEvent} extends {@code PlayerMoveEvent} but declares its own handler
     * list, so a handler registered for the move never sees it. On the local stack that meant a
     * player teleported onto a platform was not registered as waiting - and, the half that matters,
     * a player teleported <em>away</em> from one stayed registered, so the next arrival would have
     * been put in an arena against somebody who had taken the balloon somewhere else. Every travel
     * path on this server is a teleport (finding 118).</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(final org.bukkit.event.player.PlayerTeleportEvent event) {
        onMove(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY()
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
     * A death inside the arena ends the duel, and nothing else about it is ordinary.
     *
     * <p>{@code setCancelled} is not available on a death, so the drops are emptied here instead: the
     * loadout was the arena's, not the player's, and letting it fall on the floor would turn every
     * duel into a source of free iron.
     *
     * <p>The death message is cleared for the same reason the drops are: a duel costs nobody
     * anything, both people are told the outcome by {@link Duels}, and a server-wide "was slain by"
     * for a consequence-free sparring match would make the real death line mean less. Clearing it
     * here rather than teaching {@code SystemLines} about duels is deliberate - a null death message
     * already means "somebody decided this is not news", which is the same thing
     * {@code showDeathMessages} says, and it needs no agreement about event priorities.
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
        // Decided on the next tick, and that is not tidiness. GraveListener asks "is this player
        // in an arena?" at HIGH to skip the grave and the death penalty, and this runs at LOWEST -
        // so deciding here removed them from the arena before that question was asked, and a duel
        // death cost the loser 10 aura for losing AND 5 for dying, in the one place the concept
        // says a death costs nothing (finding 120).
        Bukkit.getScheduler().runTask(plugin, () -> duels.decide(player));
    }

    /**
     * The blow that would have killed a fighter ends the duel instead.
     *
     * <p>Decided by the owner on 2026-09-06: a duel ends with both fighters at the spawn and a
     * title saying who won - <b>no death screen</b>. A sparring match that costs nothing should not
     * put somebody through the same red screen as a real death, and cancelling the lethal blow is
     * the only way to avoid it: there is no way to skip the screen once the death has happened.</p>
     *
     * <p>{@code HIGHEST} and {@code ignoreCancelled}, so anything that would have stopped the
     * damage anyway still does. The wind-down runs on the next tick for the same reason the rest of
     * this class does (finding 119) - and the death path below stays, because {@code /kill}, the
     * void and a plugin calling {@code setHealth(0)} fire no damage event at all.</p>
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
            if (platform.getKey().contains(at.getWorld().getName(), at.getBlockX(),
                    at.getBlockY(), at.getBlockZ())) {
                return Optional.of(platform.getValue());
            }
        }
        return Optional.empty();
    }
}
