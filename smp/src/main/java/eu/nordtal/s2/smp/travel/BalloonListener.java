package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.region.Boxes;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Stepping into a balloon opens the travel GUI; clicking in it travels.
 *
 * The GUI opens once on entering the box, not on every step inside it.
 */
public final class BalloonListener implements Listener {

    private final Boxes balloons;
    private final Worlds worlds;
    private final SeasonState season;
    /** The milestone track as a supplier, because {@code /smp reload} replaces it. */
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final Messages messages;
    private final Identities identities;
    private final SmpSounds sounds;
    private final WorldEffects effects;

    /** Who is currently standing in a balloon, so entry is an edge and not a state. */
    private final Map<UUID, Boolean> inside = new ConcurrentHashMap<>();

    public BalloonListener(
            final Boxes balloons,
            final Worlds worlds,
            final SeasonState season,
            final java.util.function.Supplier<MilestoneTrack> track,
            final Messages messages,
            final Identities identities,
            final SmpSounds sounds,
            final WorldEffects effects) {
        this.balloons = balloons;
        this.worlds = worlds;
        this.season = season;
        this.track = track;
        this.messages = messages;
        this.identities = identities;
        this.sounds = sounds;
        this.effects = effects;
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
        final boolean nowInside =
                balloons.contains(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        final boolean wasInside = inside.getOrDefault(player.getUniqueId(), Boolean.FALSE);

        if (nowInside == wasInside) {
            return;
        }
        inside.put(player.getUniqueId(), nowInside);
        if (nowInside) {
            open(player, to);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BalloonGui gui)) {
            return;
        }
        // Nothing in this inventory is ever picked up, including the filler.
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            gui.click(player, event.getRawSlot());
        }
    }

    private void open(final Player player, final Location at) {
        final Optional<WorldRole> role = worlds.roleOf(at.getWorld());
        if (role.isEmpty()) {
            return;
        }
        player.openInventory(
                new BalloonGui(messages, identities, worlds, season, track.get(), sounds, effects, player, role.get())
                        .getInventory());
    }
}
