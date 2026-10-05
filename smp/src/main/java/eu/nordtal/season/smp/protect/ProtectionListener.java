package eu.nordtal.season.smp.protect;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.region.Boxes;
import java.util.Objects;
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
 * Building, fire, fluids and every {@link Container} are blocked; doors and switches stay free, and admins are exempt.
 */
public final class ProtectionListener implements Listener {

    private final Boxes regions;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public ProtectionListener(
            final Boxes regions, final Identities identities, final MessageRenderer renderer, final SmpSounds sounds) {
        this.regions = regions;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
    }

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
        final Location at = Objects.requireNonNull(event.getEntity().getLocation());
        if (!inside(at)) {
            return;
        }
        if (event.getRemover() instanceof Player player
                && identities.of(player.getUniqueId()).admin()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (inside(Objects.requireNonNull(event.getBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        if (!inside(Objects.requireNonNull(event.getBlock().getLocation()))) {
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
        // Only the destination matters: a river outside may not run in; a source inside was placed there by an admin.
        if (inside(Objects.requireNonNull(event.getToBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        // Only the blocks inside a box are spared; cancelling the whole explosion would also stop it hurting players.
        event.blockList().removeIf(block -> inside(Objects.requireNonNull(block.getLocation())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList().removeIf(block -> inside(Objects.requireNonNull(block.getLocation())));
    }

    private boolean inside(final Location location) {
        return regions.contains(
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Whether this player must be stopped here, telling them why once when they are. */
    private boolean deny(final Player player, final Block block) {
        if (!inside(Objects.requireNonNull(block.getLocation()))
                || identities.of(player.getUniqueId()).admin()) {
            return false;
        }
        player.sendActionBar(renderer.format(
                identities.languageOf(player.getUniqueId()),
                MESSAGES.smp().protect().denied()));
        sounds.play(player, Feedback.REFUSED);
        return true;
    }
}
