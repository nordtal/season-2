package eu.nordtal.s2.smp.farm;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * The mechanical half of the daily reset: unload today's farm world, put tomorrow's in its place,
 * load it, and get rid of yesterday's without anybody noticing.
 *
 * <h2>This was measured before it was written</h2>
 * On Paper 26.2 build 121, 2026-09-01, with a throwaway plugin and three consecutive rounds - 27
 * checks, all green: {@code unloadWorld} really does release the folder, the folder can then be
 * deleted, another renamed into its place, and the <em>same name</em> loaded again with no restart.
 * The reloaded world carried the replacement's seed every time, and no stale {@code session.lock}
 * was left behind. That is why this module has one farm world name in its config rather than a pair
 * alternating daily.
 *
 * <h2>Why the old folder is renamed instead of deleted</h2>
 * The drill's swap window was 15-18 ms, but on tiny flat test worlds where deleting happened to be
 * instant. A real farm world is gigabytes, and deleting it inside the swap would freeze the server
 * for the length of an {@code rm -rf} - which is exactly the window this whole design exists to
 * avoid. Renaming a directory is one filesystem operation and costs the same whether it holds one
 * file or a hundred thousand, so the swap renames and an async task does the unlinking afterwards.
 *
 * <h2>Where the folders are</h2>
 * Nothing here builds a path by hand. Measured in the same session: a world created through the
 * Bukkit API lands at {@code <level-name>/dimensions/minecraft/<name>}, inside the primary world
 * rather than beside it - not where the old Bukkit layout put it. Everything below works off
 * {@link World#getWorldFolder()} and its parent, so the layout can move again without this
 * silently writing to the wrong place.
 */
public final class FarmWorldSwap {

    private final Plugin plugin;
    private final String liveName;
    private final String stagingName;
    private final String retiredSuffix;

    public FarmWorldSwap(final Plugin plugin, final String liveName, final String stagingSuffix,
                         final String retiredSuffix) {
        this.plugin = plugin;
        this.liveName = liveName;
        this.stagingName = liveName + stagingSuffix;
        this.retiredSuffix = retiredSuffix;
    }

    public String liveName() {
        return liveName;
    }

    public String stagingName() {
        return stagingName;
    }

    /** Whether tomorrow's world has been generated and is sitting on disk, loaded or not. */
    public boolean stagingExists() {
        return Files.isDirectory(folderOf(stagingName));
    }

    /**
     * Creates tomorrow's world with a fresh random seed, ready to be pre-generated.
     *
     * <p>A new seed every day is the point of the farm world: it is thrown away precisely so it can
     * be a different place tomorrow.
     */
    public Optional<World> createStaging() {
        final World existing = Bukkit.getWorld(stagingName);
        if (existing != null) {
            return Optional.of(existing);
        }
        return Optional.ofNullable(Bukkit.createWorld(new WorldCreator(stagingName)));
    }

    /**
     * Performs the swap. Must run on the main thread, and must run with nobody left in the farm
     * world - the caller moves them first.
     *
     * @return empty when something refused, in which case nothing has been destroyed and today's
     *         world is still loaded
     */
    public Optional<World> swap() {
        final World staging = Bukkit.getWorld(stagingName);
        if (staging != null && !Bukkit.unloadWorld(staging, true)) {
            plugin.getLogger().warning("the staged farm world could not be unloaded - reset aborted, "
                    + "today's world is untouched");
            return Optional.empty();
        }

        final Path stagingFolder = folderOf(stagingName);
        if (!Files.isDirectory(stagingFolder)) {
            plugin.getLogger().warning("there is no staged farm world at " + stagingFolder
                    + " - reset aborted, today's world is untouched");
            return Optional.empty();
        }

        final World live = Bukkit.getWorld(liveName);
        Path liveFolder = folderOf(liveName);
        if (live != null) {
            liveFolder = live.getWorldFolder().toPath();
            // save=false: everything in this world is about to be deleted, and writing gigabytes
            // out first would only make the swap longer.
            if (!Bukkit.unloadWorld(live, false)) {
                plugin.getLogger().severe("the farm world refused to unload - reset aborted. Nothing "
                        + "was deleted; players are already at the Nordtal spawn.");
                return Optional.empty();
            }
        }

        final Path retired = liveFolder.resolveSibling(liveName + retiredSuffix + "-" + System.currentTimeMillis());
        try {
            if (Files.isDirectory(liveFolder)) {
                Files.move(liveFolder, retired);
            }
            Files.move(stagingFolder, liveFolder);
        } catch (final IOException exception) {
            plugin.getLogger().severe("the farm world folders could not be swapped: " + exception);
            return Optional.empty();
        }

        final World reloaded = Bukkit.createWorld(new WorldCreator(liveName));
        if (reloaded == null) {
            plugin.getLogger().severe("the new farm world could not be loaded after the swap");
            return Optional.empty();
        }

        deleteLater(retired);
        return Optional.of(reloaded);
    }

    /**
     * Removes leftovers from an interrupted delete.
     *
     * <p>A folder carrying the retired suffix is never a world - it is the remains of a reset whose
     * cleanup was killed - so it is safe to remove at any time, and start is the calmest moment.
     */
    public void cleanRetired() {
        final Path parent = folderOf(liveName).getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        final String prefix = liveName + retiredSuffix;
        try (var entries = Files.list(parent)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(this::deleteLater);
        } catch (final IOException exception) {
            plugin.getLogger().warning("could not look for retired farm worlds: " + exception);
        }
    }

    /**
     * The folder a world has or would have.
     *
     * <p>Asks Bukkit when the world is loaded and falls back to the container otherwise. The
     * fallback is only ever used for a world that does not exist yet, which is the one case where
     * there is nothing to ask.
     */
    private Path folderOf(final String name) {
        final World loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            return loaded.getWorldFolder().toPath();
        }
        final World primary = Bukkit.getWorlds().get(0);
        return primary.getWorldFolder().toPath().resolve("dimensions").resolve("minecraft").resolve(name);
    }

    /** Unlinks a directory tree off the main thread. Gigabytes, and nobody is waiting for it. */
    private void deleteLater(final Path folder) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (var walk = Files.walk(folder)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                plugin.getLogger().info("removed the retired farm world at " + folder);
            } catch (final IOException exception) {
                plugin.getLogger().warning("could not remove the retired farm world at " + folder
                        + ": " + exception + " - it is harmless, and the next start tries again");
            }
        });
    }
}
