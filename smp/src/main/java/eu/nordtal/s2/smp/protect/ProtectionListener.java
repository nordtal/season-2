package eu.nordtal.s2.smp.protect;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.region.Boxes;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * The four spawns, protected by a handful of event handlers over a list of boxes.
 *
 * <p><b>Not WorldGuard.</b> What is needed is exactly this - a few handlers over a few fixed boxes -
 * and not a region system with claims, flags and ownership; that argument is docs/smp.md#spawns',
 * and it also avoids a large third-party dependency whose Minecraft 26.2 availability is unverified.
 *
 * <h2>What is blocked and what is not</h2>
 * Decided 2026-09-01. Blocked: placing, breaking, explosions, fire, fluid flow, and hanging things.
 * Free: doors, trapdoors, fence gates, buttons, levers and pressure plates - because a tavern with
 * doors that do not open is a museum.
 *
 * <p>The line between the two is drawn at {@link Container} rather than at a list of materials:
 * anything that holds an inventory - chests, barrels, furnaces, hoppers, dispensers, shulker boxes -
 * is blocked, and everything else is allowed. A material list would have to be extended by hand on
 * every Minecraft update that adds a storage block, and the update where somebody forgets is the
 * update where the spawn quietly becomes the community warehouse.
 *
 * <p>The balloon, the NPC, the boards and the wheel are unaffected by any of this: they are entities
 * and plugin surfaces, not blocks.
 *
 * <p>Admins are exempt, from {@link Identities}' cache rather than from a query: this listener
 * asks the question on every block interaction, and a round trip per click is the main-thread
 * mistake this repository has already made twice.
 */
public final class ProtectionListener implements Listener {

    private final Boxes regions;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;

    public ProtectionListener(final Boxes regions, final Identities identities,
                              final Messages messages, final PlayerLocales locales) {
        this.regions = regions;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
    }

    // ------------------------------------------------------------------ players

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(final BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(final BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Container)) {
            return;
        }
        if (deny(event.getPlayer(), block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingPlace(final HangingPlaceEvent event) {
        if (event.getPlayer() != null && deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final Location at = event.getEntity().getLocation();
        if (!inside(at)) {
            return;
        }
        if (event.getRemover() instanceof Player player && identities.of(player.getUniqueId()).admin()) {
            return;
        }
        event.setCancelled(true);
    }

    // ------------------------------------------------------------------ the world itself

    @EventHandler(ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (inside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        if (!inside(event.getBlock().getLocation())) {
            return;
        }
        final Player player = event.getPlayer();
        if (player != null && identities.of(player.getUniqueId()).admin()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        // Only the destination matters: a river outside may not run in, and a source inside a
        // protected box was placed by an admin who meant it.
        if (inside(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        // Only the blocks inside a box are spared. Cancelling the whole explosion would also stop
        // it damaging players, and PvP is on everywhere.
        event.blockList().removeIf(block -> inside(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList().removeIf(block -> inside(block.getLocation()));
    }

    // ------------------------------------------------------------------ helpers

    private boolean inside(final Location location) {
        return regions.contains(location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
    }

    /** Whether this player must be stopped here - and tells them why, once, when they are. */
    private boolean deny(final Player player, final Block block) {
        if (!inside(block.getLocation()) || identities.of(player.getUniqueId()).admin()) {
            return false;
        }
        player.sendActionBar(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()), "smp.protect.denied"));
        return true;
    }
}
