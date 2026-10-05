package eu.nordtal.season.hungergames.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code hunger-games}' groups load from their defaults and that a stored list comes back whole.
 *
 * A nested interface without {@code @ConfigSpec} fails only when its defaults are written out.
 */
class HungerGamesCheckTest {

    private static final Group<HungerGamesSpec> CONFIG =
            Group.of("config", HungerGamesSpec.class).checkedBy(HungerGamesCheck::check);

    private final MemorySettingStore store = new MemorySettingStore();

    /** Loads every group from nothing stored, which serialises every nested spec. */
    @Test
    void everyGroupLoadsFromItsDefaults() throws Exception {
        final HungerGamesSpec config = store.checked("hunger-games", CONFIG, Map.of());
        final SoundsSpec sounds = store.checked("hunger-games", Group.of("sounds", SoundsSpec.class), Map.of());

        assertEquals("hunger_games", config.worldName());
        assertFalse(config.refillTiers().isEmpty());
        assertFalse(config.lootPoints().isEmpty());
        assertEquals("minecraft:entity.villager.no", sounds.loss().key());
    }

    /** A list of sections is one stored value, and every value below its nested interfaces comes back. */
    @Test
    void aStoredListOfSectionsComesBackWithItsValues() throws Exception {
        final HungerGamesSpec defaults = store.checked("hunger-games", CONFIG, Map.of());
        final Gson gson = Specs.gsonBuilder().create();

        final HungerGamesSpec reread = new MemorySettingStore()
                .checked(
                        "hunger-games",
                        CONFIG,
                        Map.of(
                                "loot-points", gson.toJson(defaults.lootPoints()),
                                "refill-tiers", gson.toJson(defaults.refillTiers())));

        assertEquals(defaults.lootPoints().size(), reread.lootPoints().size());
        assertEquals(defaults.refillTiers().size(), reread.refillTiers().size());
        assertEquals(
                defaults.lootPoints().getFirst().label(),
                reread.lootPoints().getFirst().label());
        assertEquals(
                defaults.refillTiers().getFirst().items(),
                reread.refillTiers().getFirst().items());
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
            return raw.isInterface() && raw.getName().startsWith("eu.nordtal.season.hungergames.")
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
