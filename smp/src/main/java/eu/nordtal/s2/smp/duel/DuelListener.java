package eu.nordtal.s2.smp.duel;

import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.region.Box;

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

    public DuelListener(final SmpSpec config, final Duels duels) {
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
        duels.decide(player);
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
