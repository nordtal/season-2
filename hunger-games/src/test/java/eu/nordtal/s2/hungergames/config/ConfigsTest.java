package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code hunger-games}' three config files can be written into an empty directory and read
 * back.
 *
 * <h2>Why this file exists at all</h2>
 * It did not until 2026-09-04, which made this the last module in the repository with configs and no
 * {@code ConfigsTest} - the gap {@code smp}'s own {@code ConfigsTest} was written to close and that
 * nobody then checked for anywhere else. What it cost {@code smp} was the whole plugin: four nested
 * interfaces carried no {@code @ConfigSpec}, so writing a fresh {@code config.yml} fell through to
 * Gson's reflective adapter over the interface proxy and died on {@code java.lang.reflect.Proxy#h} -
 * {@code onEnable} threw on every start, and Paper disabled the plugin while the server carried on.
 * A green test suite said nothing about it, because not one test had ever called
 * {@link Configs#load}.
 *
 * <p>Nothing was actually wrong here when this was written; all three of {@code HungerGamesSpec}'s
 * nested interfaces are annotated. That is the point - the check is cheap and the failure it guards
 * against is silent, server-side and total.</p>
 */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    /**
     * Every handle, an empty directory, and nothing else.
     *
     * <p>Loading is what writes the file, and writing is what serialises every nested spec - so a
     * missing {@code @ConfigSpec} anywhere below these roots stops here rather than in
     * {@code onEnable}.
     */
    @Test
    void aFreshDirectoryGetsAllThreeFiles() throws Exception {
        final HungerGamesSpec config = Configs.load(directory, LOGGER).get();
        final DatabaseSpec database = Configs.database(directory, LOGGER).get();
        final SoundsSpec sounds = Configs.sounds(directory, LOGGER).get();

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
        final HungerGamesSpec written = Configs.load(directory, LOGGER).get();
        final HungerGamesSpec reread = Configs.load(directory, LOGGER).get();

        assertEquals(written.lootPoints().size(), reread.lootPoints().size());
        assertEquals(written.refillTiers().size(), reread.refillTiers().size());
        assertEquals(written.lootPoints().getFirst().label(), reread.lootPoints().getFirst().label());
        assertEquals(written.refillTiers().getFirst().items(), reread.refillTiers().getFirst().items());
        assertEquals(written.lobby().broadcastIntervalSeconds(), reread.lobby().broadcastIntervalSeconds());
    }

    /**
     * {@code config.yml} does not carry the sounds, and a config that still does must not load.
     *
     * <p>jcore stops a load on a key the interface does not declare, so this is already true - it is
     * asserted by name because the <em>reason</em> it has to stay true is invisible from
     * {@link HungerGamesSpec}: a sounds block back in {@code config.yml} would be read once at enable
     * and never again, because {@code /hg reload} deliberately re-reads nothing out of that file.
     * The escape hatch of blanking a key would then silently need a restart of the event server, in
     * the middle of the one hour a year it is used.
     */
    @Test
    void configYmlRefusesASoundsBlock() throws Exception {
        Configs.load(directory, LOGGER);
        final Path file = directory.resolve("config.yml");
        Files.writeString(file, Files.readString(file) + System.lineSeparator()
                + "sounds:" + System.lineSeparator()
                + "  loss:" + System.lineSeparator()
                + "    key: minecraft:entity.villager.no" + System.lineSeparator());

        final ConfigException refused =
                assertThrows(ConfigException.class, () -> Configs.load(directory, LOGGER));
        assertTrue(refused.getMessage().contains("sounds"),
                "the refusal has to name the key, or nobody can act on it: " + refused.getMessage());
    }

    /**
     * The same rule stated directly, so that it holds whatever the test JVM has open.
     *
     * <p>The round trip above only fails on a missing annotation because {@code java.lang.reflect}
     * is closed to the test worker, which is a property of the JVM the build happens to start and
     * not of the code. A future toolchain that opened it would make the round trip pass on a plugin
     * that still dies on a real server. This walks the same interfaces and asks the question
     * outright.
     */
    @Test
    void everyNestedSpecInterfaceCarriesTheAnnotation() {
        final List<String> missing = new ArrayList<>();
        final Set<Class<?>> seen = new LinkedHashSet<>();
        for (final Class<?> root : List.of(HungerGamesSpec.class, DatabaseSpec.class, SoundsSpec.class)) {
            collectMissing(root, seen, missing);
        }
        assertTrue(missing.isEmpty(),
                "a nested spec interface without @ConfigSpec makes jcore's writer fall back to "
                        + "reflection over the proxy, which fails as a Gson error naming Proxy#h: "
                        + missing);
    }

    private static void collectMissing(final Class<?> spec, final Set<Class<?>> seen,
                                       final List<String> missing) {
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
                    ? List.of(raw) : List.of();
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
