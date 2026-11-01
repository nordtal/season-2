package eu.nordtal.season.hungergames.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.SettingsException;
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

    /** Each rule the spec declares where a hand-written check stood, with the sentence of its refusal. */
    @Test
    void theDeclaredRulesRefuseWhatTheCheckRefused() throws Exception {
        final HungerGamesSpec defaults = store.checked("hunger-games", CONFIG, Map.of());
        final Gson gson = Specs.gsonBuilder().create();
        final List<Map<String, Object>> points = new ArrayList<>();
        defaults.lootPoints().forEach(point -> points.add(Map.of("label", point.label())));
        final List<Map<String, Object>> twiceSpawn = new ArrayList<>(points);
        twiceSpawn.set(1, Map.of("label", points.getFirst().get("label")));
        final List<Map<String, Object>> blankLabel = new ArrayList<>(points);
        blankLabel.set(2, Map.of("label", ""));
        final Map<String, Object> early = Map.of("delay-minutes", 5, "items", List.of("minecraft:bread"));
        final Map<String, Object> late = Map.of("delay-minutes", 10, "items", List.of("minecraft:bread"));
        assertAll(
                () -> assertRefused(Map.of("countdown-seconds", 0), "countdown-seconds must be greater than zero"),
                () -> assertRefused(
                        Map.of("soft-minimum-participants", 1), "soft-minimum-participants must be at least 2"),
                () -> assertRefused(
                        Map.of("border-start-diameter", 1.0),
                        "border-start-diameter must be greater than border-end-diameter"),
                () -> assertRefused(
                        Map.of("border-wall-speed-blocks-per-second", 0.0),
                        "border-wall-speed-blocks-per-second must be greater than zero"),
                () -> assertRefused(Map.of("spawn-tower-radius", -1.0), "spawn-tower-radius must be greater than zero"),
                () -> assertRefused(
                        Map.of("border-end-diameter", 0.0), "border-end-diameter must be greater than zero"),
                () -> assertRefused(
                        Map.of("border-quiet-period-seconds", 0),
                        "border-quiet-period-seconds must be greater than zero"),
                () -> assertRefused(
                        Map.of("border-passive-shrink-blocks-per-hour", 0.0),
                        "border-passive-shrink-blocks-per-hour must be greater than zero"),
                () -> assertRefused(
                        Map.of("pvp-protection-seconds", 0), "pvp-protection-seconds must be greater than zero"),
                () -> assertRefused(Map.of("world-name", ""), "world-name must not be empty"),
                () -> assertRefused(
                        Map.of("loot-points", gson.toJson(points.subList(0, 4))),
                        "loot-points must have exactly 5 entries, had 4"),
                () -> assertRefused(
                        Map.of("loot-points", gson.toJson(twiceSpawn)), "loot-points[1].label holds spawn twice"),
                () -> assertRefused(
                        Map.of("loot-points", gson.toJson(blankLabel)), "loot-points[2].label must not be empty"),
                () -> assertRefused(Map.of("refill-tiers", "[]"), "refill-tiers must not be empty"),
                () -> assertRefused(
                        Map.of("refill-tiers", gson.toJson(List.of(late, early))),
                        "refill-tiers[1].delay-minutes must be in ascending order"),
                () -> assertRefused(
                        Map.of("refill-tiers", gson.toJson(List.of(early, early))),
                        "refill-tiers[1].delay-minutes holds 5 twice"),
                () -> assertRefused(
                        Map.of("refill-tiers", gson.toJson(List.of(Map.of("delay-minutes", -1)))),
                        "refill-tiers[0].delay-minutes must not be negative"),
                () -> assertRefused(
                        Map.of("refill-tiers", gson.toJson(List.of(Map.of("delay-minutes", 0, "items", List.of())))),
                        "refill-tiers[0].items must not be empty"));
    }

    private static void assertRefused(final Map<String, ?> values, final String sentence) {
        final SettingsException refused = assertThrows(
                SettingsException.class, () -> new MemorySettingStore().checked("hunger-games", CONFIG, values));
        assertTrue(String.valueOf(refused.getMessage()).contains(sentence), refused.getMessage());
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
