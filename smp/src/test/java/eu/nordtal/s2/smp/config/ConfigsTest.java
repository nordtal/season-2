package eu.nordtal.s2.smp.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
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

/** That the {@code smp} config files can be written into an empty directory and read back. */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    /**
     * Loads every handle into an empty directory, so a missing {@code @ConfigSpec} fails here, not in {@code onEnable}.
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
     * {@code config.yml} does not carry the sounds: a {@code sounds:} block there is dropped on load.
     *
     * Sounds in {@code config.yml} would never reload, so nobody may re-declare them in {@code SmpSpec}.
     */
    @Test
    void configYmlDropsASoundsBlock() throws Exception {
        Configs.load(directory, LOGGER);
        final Path file = directory.resolve("config.yml");
        Files.writeString(
                file,
                Files.readString(file) + System.lineSeparator()
                        + "sounds:" + System.lineSeparator()
                        + "  select:" + System.lineSeparator()
                        + "    key: minecraft:ui.button.click" + System.lineSeparator());

        Configs.load(directory, LOGGER);

        assertFalse(
                Files.readAllLines(file).contains("sounds:"),
                "a sounds block in config.yml has to be gone after one load, not merely ignored");
    }

    /** {@code admin-permissions} is retired: the loader drops it, so nobody re-declares it as a no-op. */
    @Test
    void configYmlDropsRetiredAdminPermissions() throws Exception {
        Configs.load(directory, LOGGER);
        final Path file = directory.resolve("config.yml");
        Files.writeString(
                file,
                Files.readString(file) + System.lineSeparator()
                        + "admin-permissions:" + System.lineSeparator()
                        + "  - minecraft.command.gamemode" + System.lineSeparator());

        final SmpSpec config = Configs.load(directory, LOGGER).get();

        assertAll(
                () -> assertFalse(
                        Files.readString(file).contains("admin-permissions"),
                        "the retired key has to be gone from the file"),
                () -> assertTrue(
                        Files.readString(directory.resolve("config.yml.bak")).contains("admin-permissions"),
                        "and readable in the backup, because it is what the operator had configured"),
                () -> assertEquals(
                        "nordtal", config.worldNordtal(), "everything the file still declares survives the deletion"));
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
        assertEquals(
                written.balloons().getFirst().world(),
                reread.balloons().getFirst().world());

        // Two levels of nesting: balloon-spawn-points is a spec of specs, so all five numbers of each point check.
        final BalloonSpawnPointsSpec points = reread.balloonSpawnPoints();
        assertAll(
                () -> assertPoint(written.balloonSpawnPoints().nordtal(), points.nordtal(), "nordtal"),
                () -> assertPoint(written.balloonSpawnPoints().nether(), points.nether(), "nether"),
                () -> assertPoint(written.balloonSpawnPoints().end(), points.end(), "end"));

        final FirstJoinSpawnSpec spawn = reread.firstJoinSpawn();
        assertAll(
                () -> assertEquals(written.firstJoinSpawn().world(), spawn.world()),
                () -> assertEquals(written.firstJoinSpawn().x(), spawn.x()),
                () -> assertEquals(written.firstJoinSpawn().y(), spawn.y()),
                () -> assertEquals(written.firstJoinSpawn().z(), spawn.z()),
                () -> assertEquals(written.firstJoinSpawn().yaw(), spawn.yaw()),
                () -> assertEquals(written.firstJoinSpawn().pitch(), spawn.pitch()),
                // The world must resolve on a real server; a disagreeing default silently moves no first join anywhere.
                () -> assertEquals(
                        reread.worldNordtal(),
                        spawn.world(),
                        "first-join-spawn's default world has to be the build world's default name,"
                                + " or a fresh config.yml ships a first join that goes nowhere"));
    }

    /** Every number of one landing point, because a null only shows up when it is read. */
    private static void assertPoint(final SpawnPointSpec written, final SpawnPointSpec reread, final String which) {
        assertAll(
                () -> assertEquals(written.x(), reread.x(), which + ": x"),
                () -> assertEquals(written.y(), reread.y(), which + ": y"),
                () -> assertEquals(written.z(), reread.z(), which + ": z"),
                () -> assertEquals(written.yaw(), reread.yaw(), which + ": yaw"),
                () -> assertEquals(written.pitch(), reread.pitch(), which + ": pitch"));
    }

    /**
     * The same rule stated directly, so it holds even on a JVM that opens {@code java.lang.reflect} to the test worker.
     */
    @Test
    void everyNestedSpecInterfaceCarriesTheAnnotation() {
        final List<String> missing = new ArrayList<>();
        final Set<Class<?>> seen = new LinkedHashSet<>();
        for (final Class<?> root : List.of(SmpSpec.class, DatabaseSpec.class, MilestonesSpec.class, SoundsSpec.class)) {
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
            return raw.isInterface() && raw.getName().startsWith("eu.nordtal.s2.smp.") ? List.of(raw) : List.of();
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
