package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.inbox.AreaShape.Corner;
import eu.nordtal.season.smp.area.Polygons;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The copy of the arena's blocks, saved as Bukkit structures before a duel and placed back inside the 3D polygons.
 * The file stays until the restore, so a crash's copy is restored at the next start.
 */
final class ArenaCopy {

    static final String FILE = "duel-arena.snapshot.gz";
    static final String LEGACY_FILE = "duel-arena.nbt";

    private final Logger logger;
    private final File file;
    private final File legacyFile;
    private final @Nullable NamespacedKey legacyWorldKey;
    private final @Nullable NamespacedKey legacyOriginKey;

    ArenaCopy(final Plugin plugin) {
        this(
                plugin.getLogger(),
                new File(plugin.getDataFolder(), FILE),
                new File(plugin.getDataFolder(), LEGACY_FILE),
                new NamespacedKey(plugin, "arena-world"),
                new NamespacedKey(plugin, "arena-origin"));
    }

    ArenaCopy(final Logger logger, final File file) {
        this(logger, file, new File(Objects.requireNonNull(file.getParentFile(), "parent"), LEGACY_FILE), null, null);
    }

    private ArenaCopy(
            final Logger logger,
            final File file,
            final File legacyFile,
            final @Nullable NamespacedKey legacyWorldKey,
            final @Nullable NamespacedKey legacyOriginKey) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
        this.legacyFile = Objects.requireNonNull(legacyFile, "legacyFile");
        this.legacyWorldKey = legacyWorldKey;
        this.legacyOriginKey = legacyOriginKey;
    }

    @FunctionalInterface
    interface StructureCapturer {
        /** Captures the bounding box of {@code area} into serialized structure bytes. */
        byte[] capture(PolygonArea area) throws IOException;
    }

    @FunctionalInterface
    interface StructureLoader {
        /** Loads the palette block states (at relative coordinates inside the bounding box) from serialized bytes. */
        List<BlockState> load(byte[] bytes) throws IOException;
    }

    @FunctionalInterface
    interface BlockPlacer {
        /** Places one captured {@code state} at world coordinates {@code x}, {@code y}, {@code z}. */
        void place(String world, int x, int y, int z, BlockState state);
    }

    record SavedArea(String name, String world, List<Corner> corners, int minY, int maxY, String structureBase64) {}

    record Snapshot(List<SavedArea> areas) {}

    record BlockKey(String world, int x, int y, int z) {}

    /** Returns false when the copy could not be written, or an older one is still not placed back. */
    boolean save(final PolygonAreas arena) {
        return save(arena, world -> Bukkit.getWorld(world) != null, ArenaCopy::captureStructure, this::restore);
    }

    boolean save(
            final PolygonAreas arena,
            final Predicate<String> worldLoaded,
            final StructureCapturer capturer,
            final Runnable restoreExisting) {
        if (file.exists() || legacyFile.exists()) {
            // An earlier copy that was never placed back holds the arena as it was before that duel.
            restoreExisting.run();
        }
        if (file.exists() || legacyFile.exists()) {
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
        final List<SavedArea> savedAreas = new ArrayList<>();
        try {
            for (final PolygonArea area : arena.all()) {
                final byte[] bytes = capturer.capture(area);
                savedAreas.add(new SavedArea(
                        area.name(),
                        area.world(),
                        area.corners(),
                        area.minY(),
                        area.maxY(),
                        Base64.getEncoder().encodeToString(bytes)));
            }
            final File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8))) {
                Json.gson().toJson(new Snapshot(savedAreas), out);
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
                ArenaCopy::loadStructure,
                ArenaCopy::placeBlockState,
                ArenaCopy::removeEntities,
                this::restoreLegacy);
    }

    void restore(
            final Predicate<String> worldLoaded,
            final StructureLoader loader,
            final BlockPlacer placer,
            final Consumer<PolygonAreas> entityCleaner) {
        restore(worldLoaded, loader, placer, entityCleaner, this::discardLegacy);
    }

    void restore(
            final Predicate<String> worldLoaded,
            final StructureLoader loader,
            final BlockPlacer placer,
            final Consumer<PolygonAreas> entityCleaner,
            final Runnable legacyRestorer) {
        if (legacyFile.exists()) {
            legacyRestorer.run();
        }
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
        if (snapshot == null || snapshot.areas() == null) {
            logger.warning("duel arena copy is empty, copy kept: file=" + file);
            return;
        }
        final List<PolygonArea> restoredAreas = new ArrayList<>();
        final List<List<BlockState>> loadedPalettes = new ArrayList<>();
        for (final SavedArea area : snapshot.areas()) {
            if (!worldLoaded.test(area.world())) {
                logger.warning("duel arena copy names no loaded world, copy kept: file=" + file);
                return;
            }
            if (area.structureBase64() == null) {
                logger.warning("duel arena copy lacks structure data, copy kept: file=" + file);
                return;
            }
            final List<BlockState> states;
            try {
                states = loader.load(Base64.getDecoder().decode(area.structureBase64()));
            } catch (final IOException | RuntimeException e) {
                logger.log(Level.WARNING, "duel arena structure unreadable, copy kept: file=" + file, e);
                return;
            }
            restoredAreas.add(new PolygonArea(area.name(), area.world(), area.corners(), area.minY(), area.maxY()));
            loadedPalettes.add(states);
        }
        final Set<BlockKey> seen = new HashSet<>();
        for (int i = 0; i < restoredAreas.size(); i++) {
            restoreArea(restoredAreas.get(i), loadedPalettes.get(i), seen, placer);
        }
        entityCleaner.accept(new PolygonAreas(restoredAreas));
        if (!file.delete()) {
            logger.warning("duel arena restored, snapshot not deleted: file=" + file);
        }
    }

    /** Places only the palette states whose world coordinates lie inside {@code area}'s 3D polygon. */
    static void restoreArea(
            final PolygonArea area,
            final List<BlockState> paletteBlocks,
            final Set<BlockKey> seen,
            final BlockPlacer placer) {
        for (final BlockState state : paletteBlocks) {
            final int x = area.minX() + state.getX();
            final int y = area.minY() + state.getY();
            final int z = area.minZ() + state.getZ();
            if (y < area.minY() || y > area.maxY() || !Polygons.containsBlock(area.corners(), x, z)) {
                continue;
            }
            if (seen.add(new BlockKey(area.world(), x, y, z))) {
                placer.place(area.world(), x, y, z, state);
            }
        }
    }

    private static byte[] captureStructure(final PolygonArea area) throws IOException {
        final World world = Objects.requireNonNull(Bukkit.getWorld(area.world()));
        final Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(
                new Location(world, area.minX(), area.minY(), area.minZ()),
                new BlockVector(
                        area.maxX() - area.minX() + 1, area.maxY() - area.minY() + 1, area.maxZ() - area.minZ() + 1),
                false);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bukkit.getStructureManager().saveStructure(out, structure);
        return out.toByteArray();
    }

    private static List<BlockState> loadStructure(final byte[] bytes) throws IOException {
        final Structure structure = Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(bytes));
        return structure.getPalettes().isEmpty()
                ? List.of()
                : structure.getPalettes().getFirst().getBlocks();
    }

    private static void placeBlockState(
            final String worldName, final int x, final int y, final int z, final BlockState state) {
        final World world = Objects.requireNonNull(Bukkit.getWorld(worldName));
        state.copy(new Location(world, x, y, z)).update(true, false);
    }

    /** Restores a leftover pre-polygon {@code duel-arena.nbt} once, then deletes it so it is not orphaned. */
    private void restoreLegacy() {
        if (!legacyFile.exists()) {
            return;
        }
        if (legacyWorldKey == null || legacyOriginKey == null) {
            discardLegacy();
            return;
        }
        final Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(legacyFile);
        } catch (final IOException | RuntimeException e) {
            logger.log(Level.WARNING, "legacy duel arena copy unreadable, deleting: file=" + legacyFile, e);
            deleteLegacy();
            return;
        }
        final @Nullable String worldName =
                structure.getPersistentDataContainer().get(legacyWorldKey, PersistentDataType.STRING);
        final int @Nullable [] corners =
                structure.getPersistentDataContainer().get(legacyOriginKey, PersistentDataType.INTEGER_ARRAY);
        if (worldName == null || corners == null || corners.length != 6) {
            logger.warning("legacy duel arena copy missing metadata, deleting: file=" + legacyFile);
            deleteLegacy();
            return;
        }
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            logger.warning("legacy duel arena copy names no loaded world, copy kept: file=" + legacyFile);
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
                .forEach(Entity::remove);
        logger.info("restored legacy duel arena copy: file=" + legacyFile);
        deleteLegacy();
    }

    private void discardLegacy() {
        logger.warning("discarding leftover legacy duel arena copy: file=" + legacyFile);
        deleteLegacy();
    }

    private void deleteLegacy() {
        if (legacyFile.exists() && !legacyFile.delete()) {
            logger.warning("legacy duel arena copy not deleted: file=" + legacyFile);
        }
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
                        return area.containsBlock(world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
                    })
                    .forEach(Entity::remove);
        }
    }
}
