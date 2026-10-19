package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class SmpSettingsTest {

    private static final Group<WorldsSpec> WORLDS =
            Group.of("worlds", WorldsSpec.class).checkedBy(SmpSettings::checkWorlds);
    private static final Group<SpawnSpec> SPAWN =
            Group.of("spawn", SpawnSpec.class).checkedBy(SmpSettings::checkSpawn);
    private static final Group<BoardsSpec> BOARDS =
            Group.of("boards", BoardsSpec.class).checkedBy(SmpSettings::checkBoards);
    private static final Group<DuelsSpec> DUELS =
            Group.of("duels", DuelsSpec.class).checkedBy(SmpSettings::checkDuels);

    /** Every smp group but the milestones, whose own tests cover them. */
    private static final List<Group<?>> GROUPS = List.of(
            WORLDS,
            Group.of("border", BorderSpec.class).checkedBy(SmpSettings::checkBorder),
            Group.of("wheel", WheelSpec.class).checkedBy(SmpSettings::checkWheel),
            Group.of("advancement-awards", AdvancementAwardsSpec.class).checkedBy(SmpSettings::checkAdvancementAwards),
            DUELS,
            BOARDS,
            Group.of("npc", NpcSpec.class),
            SPAWN,
            Group.of("deaths-and-graves", DeathsSpec.class).checkedBy(SmpSettings::checkDeaths),
            Group.of("sounds", SoundsSpec.class));

    private final MemorySettingStore store = new MemorySettingStore();

    /**
     * Loads every group with nothing stored.
     * A missing {@code @ConfigSpec} or a default its check refuses fails here and not in {@code onEnable}.
     */
    @Test
    void everyGroupLoadsFromItsDefaults() throws Exception {
        for (final Group<?> group : GROUPS) {
            store.checked("smp", group, Map.of());
        }
        final MilestonesSpec milestones = store.checked(
                "smp", Group.of("milestones", MilestonesSpec.class).checkedBy(SmpSettings::checkMilestones), Map.of());

        assertFalse(milestones.milestones().isEmpty());
    }

    @Test
    void aDuelLimitBelowOneIsRefused() {
        final Exception refused =
                assertThrows(Exception.class, () -> store.checked("smp", DUELS, Map.of("concurrent-duel-limit", 0)));

        assertTrue(refused.getMessage().contains("concurrent-duel-limit"), refused.getMessage());
    }

    @Test
    void theNestedListsComeBackWithTheirValues() throws Exception {
        final BoardsSpec boards = reread(BOARDS);
        final DuelsSpec duels = reread(DUELS);
        final SpawnSpec reread = reread(SPAWN);

        assertEquals(
                Specs.createDefault(BoardsSpec.class).boards().size(),
                boards.boards().size());
        assertEquals(
                Specs.createDefault(DuelsSpec.class).duelPlatforms().size(),
                duels.duelPlatforms().size());
        final SpawnSpec written = Specs.createDefault(SpawnSpec.class);
        assertEquals(written.spawnRegions().size(), reread.spawnRegions().size());

        final FirstJoinSpawnSpec spawn = reread.firstJoinSpawn();
        assertAll(
                () -> assertEquals(written.firstJoinSpawn().world(), spawn.world()),
                () -> assertEquals(written.firstJoinSpawn().x(), spawn.x()),
                () -> assertEquals(written.firstJoinSpawn().y(), spawn.y()),
                () -> assertEquals(written.firstJoinSpawn().z(), spawn.z()),
                () -> assertEquals(written.firstJoinSpawn().yaw(), spawn.yaw()),
                () -> assertEquals(written.firstJoinSpawn().pitch(), spawn.pitch()),
                // The world must resolve on a real server, and a different default would move no first join anywhere.
                () -> assertEquals(
                        Specs.createDefault(WorldsSpec.class).worldNordtal(),
                        spawn.world(),
                        "first-join-spawn's default world has to be the build world's default name,"
                                + " or a fresh deployment ships a first join that goes nowhere"));
    }

    /** Stores every leaf of the group's defaults as its own row and loads the group from those rows. */
    private static <T> T reread(final Group<T> group) throws Exception {
        final Map<String, Object> values = new LinkedHashMap<>();
        storeLeaves(Specs.gsonBuilder().create().toJsonTree(Specs.createDefault(group.spec())), "", values);
        return new MemorySettingStore().checked("smp", group, values);
    }

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

    @Test
    void everyNestedSpecInterfaceCarriesTheAnnotation() {
        final List<String> missing = new ArrayList<>();
        final Set<Class<?>> seen = new LinkedHashSet<>();
        final List<Class<?>> roots = new ArrayList<>(List.of(DatabaseSpec.class, MilestonesSpec.class));
        GROUPS.forEach(group -> roots.add(group.spec()));
        for (final Class<?> root : roots) {
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
