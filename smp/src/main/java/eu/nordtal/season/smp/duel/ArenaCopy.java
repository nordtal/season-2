package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.inbox.AreaShape.Corner;
import eu.nordtal.season.smp.area.Polygons;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * The copy of the arena's blocks, saved in the plugin folder before a duel and placed back after it.
 * Only blocks inside the 3D arena polygons are snapshotted and restored.
 * The file stays until the restore, so a crash's copy is restored at the next start.
 */
final class ArenaCopy {

    static final String FILE = "duel-arena.snapshot.gz";

    private final Logger logger;
    private final File file;

    ArenaCopy(final Plugin plugin) {
        this(plugin.getLogger(), new File(plugin.getDataFolder(), FILE));
    }

    ArenaCopy(final Logger logger, final File file) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
    }

    @FunctionalInterface
    interface BlockReader {
        String read(String world, int x, int y, int z);
    }

    @FunctionalInterface
    interface BlockWriter {
        void write(String world, int x, int y, int z, String data);
    }

    record SavedBlock(String world, int x, int y, int z, String data) {}

    record SavedArea(String name, String world, List<Corner> corners, int minY, int maxY) {}

    record Snapshot(List<SavedArea> areas, List<SavedBlock> blocks) {}

    private record BlockKey(String world, int x, int y, int z) {}

    /** Returns false when the copy could not be written, or an older one is still not placed back. */
    boolean save(final PolygonAreas arena) {
        return save(
                arena,
                world -> Bukkit.getWorld(world) != null,
                (worldName, x, y, z) -> Objects.requireNonNull(Bukkit.getWorld(worldName))
                        .getBlockAt(x, y, z)
                        .getBlockData()
                        .getAsString(),
                this::restore);
    }

    boolean save(
            final PolygonAreas arena,
            final Predicate<String> worldLoaded,
            final BlockReader reader,
            final Runnable restoreExisting) {
        if (file.exists()) {
            // An earlier copy that was never placed back holds the arena as it was before that duel.
            restoreExisting.run();
        }
        if (file.exists()) {
            logger.warning("duel arena restore failed, duel refused: file=" + file);
            return false;
        }
        if (arena.isEmpty()) {
            return false;
        }
        for (final PolygonArea area : arena.all()) {
            if (!worldLoaded.test(area.world())) {
                logger.warning("duel arena world not loaded, duel refused: world=" + area.world());
                return false;
            }
        }
        final Snapshot snapshot = capture(arena, reader);
        try {
            final File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8))) {
                Json.gson().toJson(snapshot, out);
            }
            return true;
        } catch (final IOException e) {
            logger.log(Level.WARNING, "duel arena not saved: file=" + file, e);
            return false;
        }
    }

    /** Places the copy back, removes dropped items and arrows in the arena and deletes the file. Main thread. */
    void restore() {
        restore(
                world -> Bukkit.getWorld(world) != null,
                (worldName, x, y, z, data) -> Objects.requireNonNull(Bukkit.getWorld(worldName))
                        .getBlockAt(x, y, z)
                        .setBlockData(Bukkit.createBlockData(data), false),
                ArenaCopy::removeEntities);
    }

    void restore(
            final Predicate<String> worldLoaded, final BlockWriter writer, final Consumer<PolygonAreas> entityCleaner) {
        if (!file.exists()) {
            return;
        }
        final Snapshot snapshot;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
            snapshot = Json.gson().fromJson(in, Snapshot.class);
        } catch (final IOException | RuntimeException e) {
            logger.log(Level.WARNING, "duel arena copy unreadable: file=" + file, e);
            return;
        }
        if (snapshot == null || snapshot.areas() == null || snapshot.blocks() == null) {
            logger.warning("duel arena copy is empty, copy kept: file=" + file);
            return;
        }
        final List<PolygonArea> restoredAreas = new ArrayList<>();
        for (final SavedArea area : snapshot.areas()) {
            if (!worldLoaded.test(area.world())) {
                logger.warning("duel arena copy names no loaded world, copy kept: file=" + file);
                return;
            }
            restoredAreas.add(new PolygonArea(area.name(), area.world(), area.corners(), area.minY(), area.maxY()));
        }
        for (final SavedBlock block : snapshot.blocks()) {
            writer.write(block.world(), block.x(), block.y(), block.z(), block.data());
        }
        entityCleaner.accept(new PolygonAreas(restoredAreas));
        if (!file.delete()) {
            logger.warning("duel arena restored, snapshot not deleted: file=" + file);
        }
    }

    static Snapshot capture(final PolygonAreas arena, final BlockReader reader) {
        final List<SavedArea> areas = new ArrayList<>();
        final List<SavedBlock> blocks = new ArrayList<>();
        final Set<BlockKey> seen = new HashSet<>();
        for (final PolygonArea area : arena.all()) {
            areas.add(new SavedArea(area.name(), area.world(), area.corners(), area.minY(), area.maxY()));
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    if (!Polygons.containsBlock(area.corners(), x, z)) {
                        continue;
                    }
                    for (int y = area.minY(); y <= area.maxY(); y++) {
                        if (seen.add(new BlockKey(area.world(), x, y, z))) {
                            blocks.add(new SavedBlock(area.world(), x, y, z, reader.read(area.world(), x, y, z)));
                        }
                    }
                }
            }
        }
        return new Snapshot(areas, blocks);
    }

    private static void removeEntities(final PolygonAreas areas) {
        for (final PolygonArea area : areas.all()) {
            final World world = Bukkit.getWorld(area.world());
            if (world == null) {
                continue;
            }
            final BoundingBox box = new BoundingBox(
                    area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1);
            world.getNearbyEntities(box).stream()
                    .filter(entity -> entity instanceof Item || entity instanceof AbstractArrow)
                    .filter(entity -> {
                        final Location at = entity.getLocation();
                        return area.containsPoint(world.getName(), at.getX(), at.getY(), at.getZ());
                    })
                    .forEach(Entity::remove);
        }
    }
}
