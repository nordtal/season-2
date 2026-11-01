package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.SettingRefused;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.smp.region.Box;
import eu.nordtal.season.smp.region.ConfigBoxes;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SmpSettingsTest {

    private static final Group<WorldsSpec> WORLDS = Group.of("worlds", WorldsSpec.class);
    private static final Group<SpawnSpec> SPAWN = Group.of("spawn", SpawnSpec.class);
    private static final Group<BoardsSpec> BOARDS = Group.of("boards", BoardsSpec.class);
    private static final Group<DuelsSpec> DUELS = Group.of("duels", DuelsSpec.class);

    /** Every smp group but the milestones, whose own tests cover them. */
    private static final List<Group<?>> GROUPS = List.of(
            WORLDS,
            Group.of("border", BorderSpec.class),
            Group.of("wheel", WheelSpec.class),
            Group.of("advancement-awards", AdvancementAwardsSpec.class),
            DUELS,
            BOARDS,
            Group.of("npc", NpcSpec.class),
            SPAWN,
            Group.of("deaths-and-graves", DeathsSpec.class),
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

    /** Steward writes every item it saves as a namespaced key, so a default in the other form shows as a change. */
    @Test
    void everyDefaultItemIsANamespacedKey() {
        final List<String> items = new ArrayList<>();
        DefaultSmp.WHEEL_PRIZES.forEach(prize -> items.add(prize.item()));
        DefaultSmp.DUEL_KITS.forEach(kit -> {
            items.add(kit.icon());
            kit.loadout().forEach(entry -> items.add(entry.item()));
        });
        assertEquals(
                List.of(),
                items.stream().filter(item -> !item.startsWith("minecraft:")).toList());
    }

    @Test
    void anEmptyKitListIsRefused() {
        final Exception refused =
                assertThrows(Exception.class, () -> store.checked("smp", DUELS, Map.of("duel-kits", List.of())));

        assertTrue(refused.getMessage().contains("duel-kits must not be empty"), refused.getMessage());
    }

    @Test
    void aSixthKitIsRefusedSinceTheWindowHasFiveRows() {
        final List<Map<String, Object>> kits = IntStream.range(0, 6)
                .mapToObj(index -> Map.<String, Object>of("name", "Kit " + index))
                .toList();
        final Exception refused =
                assertThrows(Exception.class, () -> store.checked("smp", DUELS, Map.of("duel-kits", kits)));

        assertTrue(refused.getMessage().contains("more than 5"), refused.getMessage());
    }

    @Test
    void aProtectedAreaTakesItsCornersInEitherOrder() throws Exception {
        final Map<String, Object> area =
                Map.of("world", "nordtal", "min-x", 10, "min-y", 80, "min-z", 4, "max-x", 0, "max-y", 60, "max-z", 0);
        final SpawnSpec spawn = store.checked("smp", SPAWN, Map.of("protected-areas", List.of(area)));

        final Box box = ConfigBoxes.of(spawn.protectedAreas()).all().getFirst();
        assertEquals(new Box("nordtal", 0, 60, 0, 10, 80, 4), box);
    }

    @Test
    void aTrialOfASixthKitNamesTheSettingAndTheRuleItBroke() {
        final List<Map<String, Object>> kits = IntStream.range(0, 6)
                .mapToObj(index -> Map.<String, Object>of("name", "Kit " + index))
                .toList();
        final SettingsException refused = assertThrows(
                SettingsException.class,
                () -> store.settings("smp")
                        .trial(
                                DUELS,
                                new SettingsChange("duels", Map.of("duel-kits", new Gson().toJson(kits)), List.of())));

        final SettingRefused named = SettingRefused.of(refused, "duels");
        assertEquals(
                Arrays.asList("duel-kits", SettingRule.MOST_ENTRIES, "5"),
                Arrays.asList(named.setting(), named.rule(), named.value()),
                "Steward words the refusal from the setting, the rule and its value");
    }

    @Test
    void aBrokenEntryOfAListNamesItsFieldAndTheLogSentenceNamesTheEntry() {
        final Group<AdvancementAwardsSpec> awards = Group.of("advancement-awards", AdvancementAwardsSpec.class);
        final String entries = new Gson()
                .toJson(List.of(Map.of("advancement", "minecraft:nether/netherite_armor", "aura", 50, "spins", 1)));
        final SettingsException refused = assertThrows(
                SettingsException.class,
                () -> store.settings("smp")
                        .trial(
                                awards,
                                new SettingsChange(
                                        "advancement-awards", Map.of("advancement-awards", entries), List.of())));

        final SettingRefused named = SettingRefused.of(refused, "advancement-awards");
        assertEquals(
                Arrays.asList("advancement-awards.aura", SettingRule.AT_MOST, "10"),
                Arrays.asList(named.setting(), named.rule(), named.value()));
        assertTrue(String.valueOf(named.getMessage()).startsWith("advancement-awards[0].aura "), named.getMessage());
    }

    /** Each rule a spec declares where a hand-written check stood, with the sentence of its refusal. */
    @Test
    void theWorldsBorderAndDeathRulesRefuseWhatTheChecksRefused() {
        assertAll(
                () -> assertRefused(WORLDS, Map.of("world-nordtal", " "), "world-nordtal must not be empty"),
                () -> assertRefused(
                        Group.of("border", BorderSpec.class),
                        Map.of("nether-border-diameter", -1),
                        "nether-border-diameter must be greater than zero"),
                () -> assertRefused(
                        Group.of("deaths-and-graves", DeathsSpec.class),
                        Map.of("death-penalty", -1),
                        "death-penalty must not be negative"),
                () -> assertRefused(
                        Group.of("deaths-and-graves", DeathsSpec.class),
                        Map.of("death-penalty-listed", -1),
                        "death-penalty-listed must not be negative"),
                () -> assertRefused(
                        Group.of("border", BorderSpec.class),
                        Map.of("end-border-diameter", 0),
                        "end-border-diameter must be greater than zero"),
                () -> assertRefused(
                        Group.of("border", BorderSpec.class),
                        Map.of("border-expansion-blocks-per-second", -0.5),
                        "border-expansion-blocks-per-second must be greater than zero"),
                () -> assertRefused(
                        Group.of("deaths-and-graves", DeathsSpec.class),
                        Map.of("grave-max-age-hours", -1),
                        "grave-max-age-hours must not be negative"));
    }

    @Test
    void theDuelRulesRefuseWhatTheChecksRefused() {
        assertAll(
                () -> assertRefused(
                        DUELS, Map.of("negotiation-area.world", ""), "negotiation-area.world must not be empty"),
                () -> assertRefused(
                        DUELS, Map.of("fighter-start-first.world", ""), "fighter-start-first.world must not be empty"),
                () -> assertRefused(
                        DUELS,
                        Map.of("fighter-start-second.world", ""),
                        "fighter-start-second.world must not be empty"),
                () -> assertRefused(
                        DUELS,
                        Map.of("duel-kits", List.of(Map.of("name", " "))),
                        "duel-kits[0].name must not be empty"),
                () -> assertRefused(DUELS, Map.of("duel-stake", -1), "duel-stake must not be negative"),
                () -> assertRefused(DUELS, Map.of("arena.world", ""), "arena.world must not be empty"),
                () -> assertRefused(DUELS, Map.of("exit.world", ""), "exit.world must not be empty"),
                () -> assertRefused(
                        DUELS,
                        Map.of("duel-kits", List.of(Map.of("name", "x".repeat(65)))),
                        "duel-kits[0].name is longer than 64 characters"));
    }

    @Test
    void theRulesOnListEntriesRefuseWhatTheChecksRefused() {
        final Map<String, Object> award = Map.of("advancement", "minecraft:story/mine_stone", "aura", 1);
        final Map<String, Object> board = Map.of("kind", "OBJECTIVE", "world", "nordtal", "width", 241);
        final Map<String, Object> prize = Map.of("item", "minecraft:diamond", "amount", 1, "weight", 0);
        final Map<String, Object> area = Map.of("world", "", "min-x", 0, "max-x", 1);
        assertAll(
                () -> assertRefused(
                        Group.of("advancement-awards", AdvancementAwardsSpec.class),
                        Map.of("advancement-awards", List.of(Map.of("advancement", "", "aura", 5))),
                        "advancement-awards[0].advancement must not be empty"),
                () -> assertRefused(
                        Group.of("advancement-awards", AdvancementAwardsSpec.class),
                        Map.of(
                                "advancement-awards",
                                List.of(Map.of("advancement", "minecraft:story/mine_stone", "aura", 5, "spins", -1))),
                        "advancement-awards[0].spins must not be negative"),
                () -> assertRefused(
                        Group.of("wheel", WheelSpec.class),
                        Map.of("wheel-prizes", List.of(Map.of("item", "", "amount", 1, "weight", 1))),
                        "wheel-prizes[0].item must not be empty"),
                () -> assertRefused(
                        Group.of("wheel", WheelSpec.class),
                        Map.of("wheel-prizes", List.of(Map.of("item", "minecraft:diamond", "amount", 0, "weight", 1))),
                        "wheel-prizes[0].amount must be greater than zero"),
                () -> assertRefused(
                        Group.of("advancement-awards", AdvancementAwardsSpec.class),
                        Map.of("advancement-awards", List.of(award)),
                        "advancement-awards[0].aura must be at least 2"),
                () -> assertRefused(
                        Group.of("wheel", WheelSpec.class),
                        Map.of("wheel-prizes", List.of()),
                        "wheel-prizes must not be empty"),
                () -> assertRefused(
                        Group.of("wheel", WheelSpec.class),
                        Map.of("wheel-prizes", List.of(prize)),
                        "wheel-prizes[0].weight must be greater than zero"),
                () -> assertRefused(BOARDS, Map.of("boards", List.of(board)), "boards[0].width must be at most 240"),
                () -> assertRefused(
                        BOARDS,
                        Map.of("boards", List.of(Map.of("kind", "OBJECTIVE", "world", "", "width", 64))),
                        "boards[0].world must not be empty"),
                () -> assertRefused(SPAWN, Map.of("nordtal-spawn.world", ""), "nordtal-spawn.world must not be empty"),
                () -> assertRefused(
                        SPAWN, Map.of("protected-areas", List.of(area)), "protected-areas[0].world must not be empty"));
    }

    private void assertRefused(final Group<?> group, final Map<String, ?> values, final String sentence) {
        final SettingsException refused =
                assertThrows(SettingsException.class, () -> new MemorySettingStore().checked("smp", group, values));
        assertTrue(String.valueOf(refused.getMessage()).contains(sentence), refused.getMessage());
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
                Specs.createDefault(DuelsSpec.class).duelKits().size(),
                duels.duelKits().size());
        assertEquals(
                Specs.createDefault(DuelsSpec.class)
                        .duelKits()
                        .getFirst()
                        .loadout()
                        .size(),
                duels.duelKits().getFirst().loadout().size());
        assertEquals(
                Specs.createDefault(DuelsSpec.class).arena().maxX(),
                duels.arena().maxX());
        final SpawnSpec written = Specs.createDefault(SpawnSpec.class);
        assertEquals(written.protectedAreas().size(), reread.protectedAreas().size());

        final FirstJoinSpawnSpec spawn = reread.nordtalSpawn();
        assertAll(
                () -> assertEquals(written.nordtalSpawn().world(), spawn.world()),
                () -> assertEquals(written.nordtalSpawn().x(), spawn.x()),
                () -> assertEquals(written.nordtalSpawn().y(), spawn.y()),
                () -> assertEquals(written.nordtalSpawn().z(), spawn.z()),
                () -> assertEquals(written.nordtalSpawn().yaw(), spawn.yaw()),
                () -> assertEquals(written.nordtalSpawn().pitch(), spawn.pitch()),
                // The world must resolve on a real server, and a different default would move no first join anywhere.
                () -> assertEquals(
                        Specs.createDefault(WorldsSpec.class).worldNordtal(),
                        spawn.world(),
                        "nordtal-spawn's default world has to be the build world's default name,"
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
