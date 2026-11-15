package eu.nordtal.season.smp.eye;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderSignal;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;

/**
 * Sends a thrown eye of ender to the nearest End portal of the map instead of to a stronghold of the game.
 * The portals are the end portal frames of the world's region files, scanned off the main thread at the start, and
 * the frames placed or broken since. Everything but the eye's target stays the game's.
 */
public final class MapPortals implements Listener {

    static final String CACHE = "end-portal-frames.tsv";

    private final FrameIndex index = new FrameIndex();
    private final Path cache;
    private final Logger log;
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(work -> {
        final Thread thread = new Thread(work, "smp-end-portal-scan");
        thread.setDaemon(true);
        return thread;
    });

    public MapPortals(final Path dataFolder, final Logger log) {
        this.cache = dataFolder.resolve(CACHE);
        this.log = log;
    }

    /** Reads the cache, then scans the region files of every overworld in the background. Main thread. */
    public void start(final List<World> worlds) {
        try {
            index.load(cache);
        } catch (final IOException | RuntimeException e) {
            log.log(Level.WARNING, "end portal cache not read, every region is scanned: file=" + cache, e);
        }
        for (final World world : worlds) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            final String name = world.getName();
            final Path regions = world.getWorldPath().resolve("region");
            scanner.execute(() -> scan(name, regions));
        }
    }

    /** Stops a running scan, which saves every region file read so far before it ends. */
    public void stop() {
        scanner.shutdownNow();
        try {
            if (!scanner.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warning("end portal scan still running at the stop: file=" + cache);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scan(final String world, final Path regions) {
        final long started = System.nanoTime();
        try {
            final FrameScan.Outcome outcome = FrameScan.world(index, world, regions);
            index.save(cache);
            log.info((outcome.stopped() ? "end portal scan stopped: world=" : "end portals found: world=") + world
                    + " portals="
                    + index.portals(world).size()
                    + " regions_read=" + outcome.read() + " regions_unchanged=" + outcome.unchanged()
                    + " regions_failed=" + outcome.failed() + " seconds="
                    + (System.nanoTime() - started) / 1_000_000_000L);
        } catch (final IOException | RuntimeException e) {
            log.log(Level.WARNING, "end portal scan failed: world=" + world + " regions=" + regions, e);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEyeThrown(final EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderSignal eye)
                || eye.getEntitySpawnReason() != CreatureSpawnEvent.SpawnReason.DEFAULT) {
            return;
        }
        final Location from = eye.getLocation();
        final World world = from.getWorld();
        switch (EyeTarget.of(index.portals(world.getName()), from.getX(), from.getZ())) {
            // The game's signalTo runs again: the eye's rise, its life and its chance to break stay vanilla.
            case EyeTarget.ToPortal(final Portal portal) ->
                eye.setTargetLocation(new Location(world, portal.x(), portal.y(), portal.z()));
            case EyeTarget.NotThrown notThrown -> event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFramePlaced(final BlockPlaceEvent event) {
        final Block block = event.getBlockPlaced();
        if (block.getType() == Material.END_PORTAL_FRAME) {
            index.placed(block.getWorld().getName(), frame(block));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFrameBroken(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (block.getType() == Material.END_PORTAL_FRAME) {
            index.broken(block.getWorld().getName(), frame(block));
        }
    }

    private static Frame frame(final Block block) {
        return new Frame(block.getX(), block.getY(), block.getZ());
    }
}
