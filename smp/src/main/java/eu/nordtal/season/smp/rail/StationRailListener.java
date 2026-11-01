package eu.nordtal.season.smp.rail;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Places, protects and removes station rails, and tells the networks when a rail may have changed or a chunk loaded.
 * Only the placer and admins break a station rail or the block under it; nothing else does.
 */
public final class StationRailListener implements Listener {

    private final RailNetworks networks;
    private final Identities identities;
    private final SmpSounds sounds;

    public StationRailListener(final RailNetworks networks, final Identities identities, final SmpSounds sounds) {
        this.networks = networks;
        this.identities = identities;
        this.sounds = sounds;
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        networks.loaded(event.getChunk());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(networks.item().recipeKey());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(final BlockPlaceEvent event) {
        final Block block = event.getBlockPlaced();
        if (!networks.item().is(event.getItemInHand())) {
            if (Tag.RAILS.isTagged(block.getType())) {
                networks.changed(block);
            }
            return;
        }
        if (block.getType() != Material.RAIL || !networks.restored()) {
            event.setCancelled(true);
            return;
        }
        networks.place(block, event.getPlayer().getUniqueId(), axisFacing(event.getPlayer()));
    }

    /** A station rail runs along the direction its placer faces. */
    private static RailShape axisFacing(final Player player) {
        final BlockFace facing = player.getFacing();
        return facing == BlockFace.EAST || facing == BlockFace.WEST ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        final Block above = block.getRelative(BlockFace.UP);
        final boolean onStation = networks.isStation(block);
        final Block station = onStation ? block : networks.isStation(above) ? above : null;
        if (station == null) {
            noteRails(block);
            return;
        }
        final Player player = event.getPlayer();
        if (!mayBreak(player, station)) {
            event.setCancelled(true);
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        if (onStation) {
            event.setDropItems(false);
        } else {
            station.setType(Material.AIR, false);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            station.getWorld()
                    .dropItemNaturally(
                            station.getLocation().add(0.5, 0.5, 0.5),
                            networks.item().create());
        }
        networks.remove(station);
    }

    private boolean mayBreak(final Player player, final Block station) {
        return identities.of(player).admin()
                || networks.station(station)
                        .map(row -> row.placedBy().equals(player.getUniqueId()))
                        .orElse(false);
    }

    /** Whether {@code block} is a station rail or the block it stands on. */
    private boolean guarded(final Block block) {
        return networks.isStation(block) || networks.isStation(block.getRelative(BlockFace.UP));
    }

    private void noteRails(final Block block) {
        if (Tag.RAILS.isTagged(block.getType())) {
            networks.changed(block);
        }
        final Block above = block.getRelative(BlockFace.UP);
        if (Tag.RAILS.isTagged(above.getType())) {
            networks.changed(above);
        }
    }

    private void explode(final List<Block> blocks) {
        blocks.removeIf(this::guarded);
        blocks.forEach(this::noteRails);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(final EntityExplodeEvent event) {
        explode(event.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(final BlockExplodeEvent event) {
        explode(event.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        final Block head = event.getBlock().getRelative(event.getDirection());
        if (guarded(head) || event.getBlocks().stream().anyMatch(this::guarded)) {
            event.setCancelled(true);
            return;
        }
        noteRails(head);
        event.getBlocks().forEach(block -> {
            noteRails(block);
            noteRails(block.getRelative(event.getDirection()));
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::guarded)) {
            event.setCancelled(true);
            return;
        }
        event.getBlocks().forEach(this::noteRails);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFlow(final BlockFromToEvent event) {
        if (guarded(event.getToBlock())) {
            event.setCancelled(true);
            return;
        }
        noteRails(event.getToBlock());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        if (guarded(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBurn(final BlockBurnEvent event) {
        if (guarded(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** A rail that loses its support breaks without a player; a station rail drops itself, not a plain rail. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDestroy(final BlockDestroyEvent event) {
        final Block block = event.getBlock();
        if (networks.isStation(block)) {
            event.setWillDrop(false);
            block.getWorld()
                    .dropItemNaturally(
                            block.getLocation().add(0.5, 0.5, 0.5),
                            networks.item().create());
            networks.remove(block);
            return;
        }
        noteRails(block);
    }
}
