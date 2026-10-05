package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** That the {@code smp} groups load from their defaults and come back whole from stored rows. */
class SmpSettingsTest {

    private static final Group<SmpSpec> CONFIG =
            Group.of("config", SmpSpec.class).checkedBy(SmpSettings::check);

    private final MemorySettingStore store = new MemorySettingStore();

    /** Loads every group from nothing stored, so a missing {@code @ConfigSpec} fails here, not in {@code onEnable}. */
    @Test
    void everyGroupLoadsFromItsDefaults() throws Exception {
        final SmpSpec config = store.checked("smp", CONFIG, Map.of());
        final MilestonesSpec milestones = store.checked(
                "smp", Group.of("milestones", MilestonesSpec.class).checkedBy(SmpSettings::checkMilestones), Map.of());
        final SoundsSpec sounds = store.checked("smp", Group.of("sounds", SoundsSpec.class), Map.of());

        assertEquals("nordtal", config.worldNordtal());
        assertFalse(milestones.milestones().isEmpty());
        assertEquals("minecraft:ui.button.click", sounds.select().key());
    }

    /** Every value below the nested interfaces survives being stored as rows, not just the flat ones. */
    @Test
    void theNestedListsComeBackWithTheirValues() throws Exception {
        final SmpSpec written = Specs.createDefault(SmpSpec.class);
        final Map<String, Object> values = new LinkedHashMap<>();
        storeLeaves(Specs.gsonBuilder().create().toJsonTree(written), "", values);
        final SmpSpec reread = new MemorySettingStore().checked("smp", CONFIG, values);

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
                                + " or a fresh deployment ships a first join that goes nowhere"));
    }

    /** Puts every leaf below {@code tree} into {@code values} as Steward stores it: a list is one value. */
    private static void storeLeaves(final JsonElement tree, final String path, final Map<String, Object> values) {
        if (tree instanceof final JsonObject object) {
            object.entrySet()
                    .forEach(entry -> storeLeaves(
                            entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), values));
            return;
        }
        if (tree.isJsonPrimitive()) {
            final var primitive = tree.getAsJsonPrimitive();
            values.put(
                    path,
                    primitive.isString()
                            ? primitive.getAsString()
                            : primitive.isBoolean() ? (Object) primitive.getAsBoolean() : primitive.getAsNumber());
            return;
        }
        values.put(path, tree.toString());
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
                "a nested spec interface without @ConfigSpec makes the schema writer fall back to "
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
            return raw.isInterface() && raw.getName().startsWith("eu.nordtal.season.smp.") ? List.of(raw) : List.of();
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
