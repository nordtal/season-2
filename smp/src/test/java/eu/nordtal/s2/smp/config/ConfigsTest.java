package eu.nordtal.s2.smp.config;

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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code smp}'s three config files can be written into an empty directory and read back.
 *
 * <h2>Why this file exists at all</h2>
 * It did not until 2026-09-02, and {@code smp} was the only module with configs and no
 * {@code ConfigsTest}. What that cost was the whole plugin: four nested interfaces in
 * {@link SmpSpec} carried no {@code @ConfigSpec}, so writing a fresh {@code config.yml} fell
 * through to Gson's reflective adapter over the interface proxy and died on
 * {@code java.lang.reflect.Proxy#h} - {@code onEnable} threw on the first load, on every start,
 * and Paper disabled the plugin while the server carried on. 135 green tests said nothing about
 * it, because not one of them had ever called {@link Configs#load}.
 *
 * <p>{@link MilestonesTest} covered {@code milestones.yml} alone, which is the one file of the
 * three whose nested interfaces <em>were</em> annotated.
 */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    /**
     * The failure B1 actually was: every handle, an empty directory, and nothing else.
     *
     * <p>Loading is what writes the file, and writing is what serialises every nested spec - so a
     * missing {@code @ConfigSpec} anywhere below these roots stops here rather than in
     * {@code onEnable}. It was three files until 2026-09-04, when the sounds became the fourth.
     */
    @Test
    void aFreshDirectoryGetsAllFourFiles() throws Exception {
        final SmpSpec config = Configs.load(directory, LOGGER).get();
        final DatabaseSpec database = Configs.database(directory, LOGGER).get();
        final MilestonesSpec milestones = Configs.milestones(directory, LOGGER).get();
        final SoundsSpec sounds = Configs.sounds(directory, LOGGER).get();

        assertTrue(Files.isRegularFile(directory.resolve("config.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("database.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("milestones.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("sounds.yml")));

        assertEquals("nordtal", config.worldNordtal());
        assertTrue(database.jdbcUrl().startsWith("jdbc:postgresql:"));
        assertFalse(milestones.milestones().isEmpty());
        assertEquals("minecraft:ui.button.click", sounds.select().key());
    }

    /**
     * {@code config.yml} does not carry the sounds, and a config that still does loses the block
     * rather than keeping something that looks like a working setting.
     *
     * <p>They lived under a {@code sounds:} key there for one afternoon on 2026-09-04 before moving
     * to their own file, for the reason {@link SoundsSpec} gives. This is asserted by name because
     * the <em>reason</em> it has to stay true is invisible from {@code SmpSpec}: a sounds block back
     * in {@code config.yml} would be read once at enable and never again, and the escape hatch of
     * blanking a key would silently need a restart of the season.
     *
     * <p>Until jcore 3.1.0 the block stopped the plugin and this test asserted that. What it pins
     * now is the half that was always the point: the key does not survive the load, so nobody can
     * re-declare it in {@code SmpSpec} and quietly get an unreloadable second source of sounds.
     */
    @Test
    void configYmlDropsASoundsBlock() throws Exception {
        Configs.load(directory, LOGGER);
        final Path file = directory.resolve("config.yml");
        Files.writeString(file, Files.readString(file) + System.lineSeparator()
                + "sounds:" + System.lineSeparator()
                + "  select:" + System.lineSeparator()
                + "    key: minecraft:ui.button.click" + System.lineSeparator());

        Configs.load(directory, LOGGER);

        assertFalse(Files.readAllLines(file).contains("sounds:"),
                "a sounds block in config.yml has to be gone after one load, not merely ignored");
    }

    /**
     * {@code admin-permissions} is retired, and a deployed {@code config.yml} that still carries it
     * loses the block instead of keeping one that reads like a working setting.
     *
     * <p>Retired 2026-09-04, when an admin became a server operator instead
     * ({@link eu.nordtal.s2.common.access.AdminOperators}). This key is in a file that already
     * exists in a production volume, and the only thing an operator could ever do about it is
     * delete the line - so as of jcore 3.1.0 the loader deletes it, names it in a warning and
     * leaves the old file in {@code config.yml.bak}. This test used to assert the plugin stopped
     * instead. What it pins either way is that nobody re-declares the key as a deprecated no-op to
     * make an upgrade quieter.</p>
     */
    @Test
    void configYmlDropsRetiredAdminPermissions() throws Exception {
        Configs.load(directory, LOGGER);
        final Path file = directory.resolve("config.yml");
        Files.writeString(file, Files.readString(file) + System.lineSeparator()
                + "admin-permissions:" + System.lineSeparator()
                + "  - minecraft.command.gamemode" + System.lineSeparator());

        final SmpSpec config = Configs.load(directory, LOGGER).get();

        assertAll(
                () -> assertFalse(Files.readString(file).contains("admin-permissions"),
                        "the retired key has to be gone from the file"),
                () -> assertTrue(Files.readString(directory.resolve("config.yml.bak"))
                                .contains("admin-permissions"),
                        "and readable in the backup, because it is what the operator had configured"),
                () -> assertEquals("nordtal", config.worldNordtal(),
                        "everything the file still declares survives the deletion")
        );
    }

    /** Every value below the nested interfaces survives the round trip, not just the flat ones. */
    @Test
    void theNestedListsComeBackWithTheirValues() throws Exception {
        final SmpSpec written = Configs.load(directory, LOGGER).get();
        final SmpSpec reread = Configs.load(directory, LOGGER).get();

        assertEquals(written.balloons().size(), reread.balloons().size());
        assertEquals(written.boards().size(), reread.boards().size());
        assertEquals(written.duelPlatforms().size(), reread.duelPlatforms().size());
        assertEquals(written.spawnRegions().size(), reread.spawnRegions().size());
        assertEquals(written.npc().world(), reread.npc().world());
        assertEquals(written.balloons().getFirst().world(), reread.balloons().getFirst().world());
    }

    /**
     * The same rule stated directly, so that it holds whatever the test JVM has open.
     *
     * <p>The round trip above only fails because {@code java.lang.reflect} is closed to the test
     * worker, which is a property of the JVM the build happens to start and not of the code. A
     * future toolchain that opened it would make the round trip pass on a plugin that still dies
     * on a real server. This walks the same interfaces and asks the question outright.
     */
    @Test
    void everyNestedSpecInterfaceCarriesTheAnnotation() {
        final List<String> missing = new ArrayList<>();
        final Set<Class<?>> seen = new LinkedHashSet<>();
        for (final Class<?> root : List.of(SmpSpec.class, DatabaseSpec.class, MilestonesSpec.class,
                SoundsSpec.class)) {
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
            return raw.isInterface() && raw.getName().startsWith("eu.nordtal.s2.smp.")
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
