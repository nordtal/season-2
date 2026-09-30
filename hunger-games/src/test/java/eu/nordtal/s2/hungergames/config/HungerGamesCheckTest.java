package eu.nordtal.s2.hungergames.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.FileSettings;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks that {@code hunger-games}' three config files can be written into an empty directory and read back.
 *
 * A nested interface without {@code @ConfigSpec} fails only when a fresh file is written.
 */
class HungerGamesCheckTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(HungerGamesCheckTest.class);

    @TempDir
    Path directory;

    /** Loads every handle into an empty directory, which serialises every nested spec. */
    @Test
    void aFreshDirectoryGetsAllThreeFiles() throws Exception {
        final HungerGamesSpec config = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("config", HungerGamesSpec.class, HungerGamesCheck::check)
                .get();
        final DatabaseSpec database = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("database", DatabaseSpec.class, DatabasePool::check)
                .get();
        final SoundsSpec sounds = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("sounds", SoundsSpec.class)
                .get();

        assertTrue(Files.isRegularFile(directory.resolve("config.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("database.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("sounds.yml")));

        assertEquals("hunger_games", config.worldName());
        assertFalse(config.refillTiers().isEmpty());
        assertFalse(config.lootPoints().isEmpty());
        assertTrue(database.jdbcUrl().startsWith("jdbc:postgresql:"));
        assertEquals("minecraft:entity.villager.no", sounds.loss().key());
    }

    /** Every value below the nested interfaces survives the round trip, not just the flat ones. */
    @Test
    void theNestedListsComeBackWithTheirValues() throws Exception {
        final HungerGamesSpec written = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("config", HungerGamesSpec.class, HungerGamesCheck::check)
                .get();
        final HungerGamesSpec reread = FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("config", HungerGamesSpec.class, HungerGamesCheck::check)
                .get();

        assertEquals(written.lootPoints().size(), reread.lootPoints().size());
        assertEquals(written.refillTiers().size(), reread.refillTiers().size());
        assertEquals(
                written.lootPoints().getFirst().label(),
                reread.lootPoints().getFirst().label());
        assertEquals(
                written.refillTiers().getFirst().items(),
                reread.refillTiers().getFirst().items());
        assertEquals(written.lobby().broadcastIntervalSeconds(), reread.lobby().broadcastIntervalSeconds());
    }

    /**
     * {@code config.yml} does not carry the sounds, and a config that still does loses the block.
     *
     * {@code /hg reload} re-reads nothing from {@code config.yml}, so sounds there would need a restart.
     */
    @Test
    void configYmlDropsASoundsBlock() throws Exception {
        FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("config", HungerGamesSpec.class, HungerGamesCheck::check);
        final Path file = directory.resolve("config.yml");
        Files.writeString(
                file,
                Files.readString(file) + System.lineSeparator()
                        + "sounds:" + System.lineSeparator()
                        + "  loss:" + System.lineSeparator()
                        + "    key: minecraft:entity.villager.no" + System.lineSeparator());

        FileSettings.in(directory, "NORDTAL_HUNGER_GAMES", LOGGER)
                .load("config", HungerGamesSpec.class, HungerGamesCheck::check);

        assertFalse(
                Files.readAllLines(file).contains("sounds:"),
                "a sounds block in config.yml has to be gone after one load, not merely ignored");
    }

    /** Checks every nested interface for {@code @ConfigSpec} directly, whatever the test JVM has open. */
    @Test
    void everyNestedSpecInterfaceCarriesTheAnnotation() {
        final List<String> missing = new ArrayList<>();
        final Set<Class<?>> seen = new LinkedHashSet<>();
        for (final Class<?> root : List.of(HungerGamesSpec.class, DatabaseSpec.class, SoundsSpec.class)) {
            collectMissing(root, seen, missing);
        }
        assertTrue(
                missing.isEmpty(),
                "a nested spec interface without @ConfigSpec makes jcore's writer fall back to "
                        + "reflection over the proxy, which fails as a Gson error naming Proxy#h: "
                        + missing);
    }

    private static void collectMissing(final Class<?> spec, final Set<Class<?>> seen, final List<String> missing) {
        if (!seen.add(spec)) {
            return;
        }
        if (!spec.isAnnotationPresent(ConfigSpec.class)) {
            missing.add(spec.getName());
        }
        for (final Method method : spec.getMethods()) {
            for (final Class<?> nested : specTypesOf(method.getGenericReturnType())) {
                collectMissing(nested, seen, missing);
            }
        }
    }

    /** An interface return type, or the interface element type of a {@code List<…>}. */
    private static List<Class<?>> specTypesOf(final Type type) {
        if (type instanceof Class<?> raw) {
            return raw.isInterface() && raw.getName().startsWith("eu.nordtal.s2.hungergames.")
                    ? List.of(raw)
                    : List.of();
        }
        if (type instanceof ParameterizedType parameterized) {
            final List<Class<?>> found = new ArrayList<>();
            for (final Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(specTypesOf(argument));
            }
            return found;
        }
        return List.of();
    }
}
