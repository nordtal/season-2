package eu.nordtal.season.smp.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Each fighter's pre-duel state on disk, written before a duel starts and deleted once restored.
 *
 * A file left after an unclean server crash is restored once when that fighter next joins.
 */
final class FighterStateStore {

    static final String DIRECTORY = "duel-fighters";

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final Path directory;
    private final Logger logger;

    FighterStateStore(final Plugin plugin) {
        this(plugin.getDataFolder().toPath().resolve(DIRECTORY), plugin.getLogger());
    }

    FighterStateStore(final Path directory, final Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    /** Returns false when the state could not be written, or an older un-restored state still exists. */
    boolean save(final UUID playerId, final StoredState state) {
        final Path file = fileOf(playerId);
        if (Files.exists(file)) {
            logger.warning("duel saved state already on disk, duel refused: player=" + playerId);
            return false;
        }
        try {
            Files.createDirectories(directory);
            final Path written = file.resolveSibling(file.getFileName() + ".new");
            Files.writeString(written, GSON.toJson(state), StandardCharsets.UTF_8);
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (final IOException e) {
            logger.log(Level.WARNING, "duel saved state not written: file=" + file, e);
            return false;
        }
    }

    boolean has(final UUID playerId) {
        return Files.isRegularFile(fileOf(playerId));
    }

    /** Reads the fighter's persisted state, or null when none exists or the file is unreadable. */
    @Nullable
    StoredState load(final UUID playerId) {
        final Path file = fileOf(playerId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final String text = Files.readString(file, StandardCharsets.UTF_8);
            final @Nullable StoredState parsed = GSON.fromJson(text, StoredState.class);
            if (parsed == null) {
                logger.warning("duel saved state empty: file=" + file);
                return null;
            }
            return parsed;
        } catch (final IOException | JsonParseException e) {
            logger.log(Level.WARNING, "duel saved state unreadable: file=" + file, e);
            return null;
        }
    }

    /**
     * Restores a persisted state at most once, deleting the file only after {@code apply} completes.
     *
     * @return whether a saved state existed and was restored
     */
    boolean restoreOnce(final UUID playerId, final Consumer<StoredState> apply) {
        final @Nullable StoredState stored = load(playerId);
        if (stored == null) {
            return false;
        }
        apply.accept(stored);
        delete(playerId);
        return true;
    }

    /** Removes the persisted file once the fighter's state has been restored. */
    void delete(final UUID playerId) {
        final Path file = fileOf(playerId);
        try {
            Files.deleteIfExists(file);
        } catch (final IOException e) {
            logger.log(Level.WARNING, "duel saved state not deleted: file=" + file, e);
        }
    }

    private Path fileOf(final UUID playerId) {
        return directory.resolve(playerId + ".json");
    }

    record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {}

    record StoredEffect(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}

    record StoredState(
            @Nullable StoredLocation location,
            List<@Nullable String> inventory,
            List<@Nullable String> armour,
            double health,
            int foodLevel,
            float saturation,
            int level,
            float experience,
            String gameMode,
            List<StoredEffect> effects) {}
}
