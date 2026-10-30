package eu.nordtal.season.smp.duel;

import eu.nordtal.season.smp.region.Box;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The copy of the arena's blocks, saved in the plugin folder before a duel and placed back after it.
 *
 * The file stays until the restore, so a crash's copy is restored at the next start, at the corners it carries.
 */
final class ArenaCopy {

    static final String FILE = "duel-arena.nbt";

    private final Plugin plugin;
    private final File file;
    private final NamespacedKey worldKey;
    private final NamespacedKey originKey;

    ArenaCopy(final Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        this.worldKey = new NamespacedKey(plugin, "arena-world");
        this.originKey = new NamespacedKey(plugin, "arena-origin");
    }

    /** Returns false when the copy could not be written; the duel is then not fought. */
    boolean save(final World world, final Box arena) {
        if (file.exists()) {
            // An earlier copy that was never placed back holds the arena as it was before that duel.
            restore();
        }
        final Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(
                new Location(world, arena.minX(), arena.minY(), arena.minZ()),
                new BlockVector(
                        arena.maxX() - arena.minX() + 1,
                        arena.maxY() - arena.minY() + 1,
                        arena.maxZ() - arena.minZ() + 1),
                false);
        structure.getPersistentDataContainer().set(worldKey, PersistentDataType.STRING, world.getName());
        structure.getPersistentDataContainer().set(originKey, PersistentDataType.INTEGER_ARRAY, new int[] {
            arena.minX(), arena.minY(), arena.minZ(), arena.maxX(), arena.maxY(), arena.maxZ()
        });
        try {
            file.getParentFile().mkdirs();
            Bukkit.getStructureManager().saveStructure(file, structure);
            return true;
        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING, "the duel arena could not be saved to " + file, e);
            return false;
        }
    }

    /** Places the copy back, removes dropped items and arrows in the arena and deletes the file. Main thread. */
    void restore() {
        if (!file.exists()) {
            return;
        }
        final Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(file);
        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING, "the duel arena copy " + file + " could not be read", e);
            return;
        }
        final @Nullable String worldName =
                structure.getPersistentDataContainer().get(worldKey, PersistentDataType.STRING);
        final int @Nullable [] corners =
                structure.getPersistentDataContainer().get(originKey, PersistentDataType.INTEGER_ARRAY);
        final World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || corners == null || corners.length != 6) {
            plugin.getLogger()
                    .warning("the duel arena copy " + file + " names no loaded world; it is kept for the next start");
            return;
        }
        structure.place(
                new Location(world, corners[0], corners[1], corners[2]),
                false,
                StructureRotation.NONE,
                Mirror.NONE,
                0,
                1f,
                new Random());
        world
                .getNearbyEntities(new BoundingBox(
                        corners[0], corners[1], corners[2], corners[3] + 1, corners[4] + 1, corners[5] + 1))
                .stream()
                .filter(entity -> entity instanceof Item || entity instanceof AbstractArrow)
                .forEach(org.bukkit.entity.Entity::remove);
        if (!file.delete()) {
            plugin.getLogger().warning("the duel arena copy " + file + " was placed back but could not be deleted");
        }
    }
}
