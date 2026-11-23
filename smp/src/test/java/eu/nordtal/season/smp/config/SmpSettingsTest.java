package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import eu.nordtal.season.settings.Position;
import eu.nordtal.season.settings.SettingRefused;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.smp.region.ConfigAreas;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.spec.SpecProperty;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SmpSettingsTest {

    private static final Group<WorldsSpec> WORLDS = Group.of("worlds", WorldsSpec.class);
    private static final Group<SpawnSpec> SPAWN = Group.of("spawn", SpawnSpec.class);
    private static final Group<BoardsSpec> BOARDS = Group.of("boards", BoardsSpec.class);
    private static final Group<DuelsSpec> DUELS = Group.of("duels", DuelsSpec.class);
    private static final Group<RailsSpec> RAILS = Group.of("rails", RailsSpec.class);
    private static final Group<CustomBorderSpec> CUSTOM_BORDER = Group.of("custom-border", CustomBorderSpec.class);

    /** Every smp group but the milestones, whose own tests cover them. */
    private static final List<Group<?>> GROUPS = List.of(
            WORLDS,
            Group.of("border", BorderSpec.class),
            Group.of("wheel", WheelSpec.class),
            Group.of("advancement-awards", AdvancementAwardsSpec.class),
            DUELS,
            BOARDS,
            SPAWN,
            Group.of("deaths-and-graves", DeathsSpec.class),
            RAILS,
            CUSTOM_BORDER,
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
    void theJunctionSettingsHoldTheirDefaultsAndRefuseValuesOutsideTheirRanges() throws Exception {
        final RailsSpec defaults = store.checked("smp", RAILS, Map.of());

        assertEquals(4, defaults.junctionSwitch());
        assertEquals(3, defaults.crawlSpeed());
        assertAll(
                () -> assertRefused(RAILS, Map.of("junction-switch", 1), "junction-switch must be at least 2"),
                () -> assertRefused(RAILS, Map.of("junction-switch", 17), "junction-switch must be at most 16"),
                () -> assertRefused(RAILS, Map.of("crawl-speed", 0), "crawl-speed must be at least 1"),
                () -> assertRefused(RAILS, Map.of("crawl-speed", 11), "crawl-speed must be at most 10"));
    }

    @Test
    void theDefaultCustomBordersHoldThePlaceholdersAndTheFiveThousandDimensionSquares() throws Exception {
        final BorderSpec border = store.checked("smp", Group.of("border", BorderSpec.class), Map.of());
        assertEquals(5000, border.netherBorderDiameter());
        assertEquals(5000, border.endBorderDiameter());

        final List<CustomBorderSpec.World> defaults =
                store.checked("smp", CUSTOM_BORDER, Map.of()).worlds();
        assertEquals(
                List.of("nordtal", "nordtal_nether", "nordtal_the_end"),
                defaults.stream().map(CustomBorderSpec.World::world).toList());
        assertTrue(defaults.stream().allMatch(CustomBorderSpec.World::enabled));

        final CustomBorderSpec.World overworld = defaults.getFirst();
        assertEquals(
                List.of("departure", "foothold", "settlement", "expanse", "frontier"),
                overworld.polygons().stream()
                        .map(CustomBorderSpec.Polygon::name)
                        .toList());
        for (final CustomBorderSpec.Polygon polygon : overworld.polygons()) {
            for (final CustomBorderSpec.Corner corner : polygon.corners()) {
                assertTrue(corner.x() >= 160 && corner.x() <= 10079, polygon.name() + " x=" + corner.x());
                assertTrue(corner.z() >= 160 && corner.z() <= 10079, polygon.name() + " z=" + corner.z());
            }
        }
        for (final CustomBorderSpec.World dimension : defaults.subList(1, 3)) {
            assertEquals(1, dimension.polygons().size());
            final List<CustomBorderSpec.Corner> corners =
                    dimension.polygons().getFirst().corners();
            assertEquals(
                    List.of(-2500, 2499, 2499, -2500),
                    corners.stream().map(CustomBorderSpec.Corner::x).toList());
            assertEquals(
                    List.of(-2500, -2500, 2499, 2499),
                    corners.stream().map(CustomBorderSpec.Corner::z).toList());
        }
        assertTrue(store.checked("smp", CUSTOM_BORDER, Map.of("worlds", List.of()))
                .worlds()
                .isEmpty());
    }

    @Test
    void customBorderReadsConfiguredAreas() throws Exception {
        final CustomBorderSpec read = store.checked(
                "smp",
                CUSTOM_BORDER,
                Map.of(
                        "worlds",
                        List.of(Map.of(
                                "world",
                                "nordtal",
                                "enabled",
                                true,
                                "polygons",
                                List.of(Map.of(
                                        "name",
                                        "spawn",
                                        "corners",
                                        List.of(
                                                Map.of("x", 1, "z", 2),
                                                Map.of("x", 3, "z", 4),
                                                Map.of("x", 5, "z", 0))))))));

        final CustomBorderSpec.World world = read.worlds().getFirst();
        assertTrue(world.enabled());
        assertEquals(3, world.polygons().getFirst().corners().get(1).x());
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
    void theBuiltInWheelPrizesCarryEveryPropertyDefaultWithoutStoredSettings() throws Exception {
        final List<List<WheelPrizeSpec>> pools = List.of(
                Specs.createDefault(WheelSpec.class).wheelPrizes(),
                store.checked("smp", Group.of("wheel", WheelSpec.class), Map.of())
                        .wheelPrizes());
        for (final List<WheelPrizeSpec> pool : pools) {
            assertFalse(pool.isEmpty());
            for (final WheelPrizeSpec prize : pool) {
                assertFalse(prize.item().isBlank());
                assertEquals("", prize.data());
                assertNotNull(prize.structure());
            }
        }
    }

    @Test
    void theWheelOffersTheThreeNewItemsAtTheirDeclaredWeights() {
        final WheelSpec wheel = Specs.createDefault(WheelSpec.class);
        final Map<String, Integer> weights =
                wheel.wheelPrizes().stream().collect(Collectors.toMap(WheelPrizeSpec::item, WheelPrizeSpec::weight));
        assertEquals(1066, weights.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(3, weights.get("minecraft:heart_of_the_sea"));
        assertEquals(3, weights.get("minecraft:vex_armor_trim_smithing_template"));
        assertEquals(60, weights.get("minecraft:buried_mineshaft_map"));
    }

    @Test
    void aWheelPrizeKeepsItsStructureTagWhenRead() throws Exception {
        final WheelSpec wheel = store.checked(
                "smp",
                Group.of("wheel", WheelSpec.class),
                Map.of(
                        "wheel-prizes",
                        List.of(Map.of(
                                "item", "minecraft:buried_mineshaft_map",
                                "structure", "minecraft:on_mineshaft_maps"))));
        final JsonElement written =
                Specs.gsonBuilder().create().toJsonTree(wheel.wheelPrizes().getFirst(), WheelPrizeSpec.class);
        assertEquals(
                "minecraft:on_mineshaft_maps",
                written.getAsJsonObject().get("structure").getAsString());
    }

    @Test
    void frontierPaysAnAncientCityMapForMiningDeepslateDiamondOre() {
        final var frontier = Specs.createDefault(MilestonesSpec.class).milestones().stream()
                .filter(entry -> entry.key().equals("frontier"))
                .findFirst()
                .orElseThrow();
        final var objective = frontier.objectives().stream()
                .filter(entry -> entry.key().equals("deepslate-diamonds"))
                .findFirst()
                .orElseThrow();
        assertEquals("STATISTIC", objective.type());
        assertEquals(64, objective.target());
        assertEquals("minecraft:mine_block", objective.statistic());
        assertEquals(List.of("minecraft:deepslate_diamond_ore"), objective.subjects());
        assertEquals(170, objective.auraBudget());
        assertEquals(10, objective.spinBudget());
        final var reward = Specs.gsonBuilder()
                .create()
                .toJsonTree(objective, MilestonesSpec.ObjectiveEntry.class)
                .getAsJsonObject()
                .getAsJsonArray("reward-item")
                .get(0)
                .getAsJsonObject();
        assertEquals("minecraft:buried_ancient_city_map", reward.get("item").getAsString());
        assertEquals("minecraft:on_ancient_city_maps", reward.get("structure").getAsString());
    }

    @Test
    void aWheelMapRequiresAKnownStructureTag() throws Exception {
        final Group<WheelSpec> wheel = Group.of("wheel", WheelSpec.class)
                .checkedBy(spec -> SmpSettings.checkWheel(spec, "minecraft:on_mineshaft_maps"::equals));
        final Map<String, ?> prize = Map.of("item", "minecraft:buried_mineshaft_map", "structure", "minecraft:missing");
        assertRefused(wheel, Map.of("wheel-prizes", List.of(prize)), "minecraft:missing");
    }

    @Test
    void anObjectiveRewardMapRequiresAKnownStructureTag() throws Exception {
        final Group<MilestonesSpec> accepted = Group.of("milestones", MilestonesSpec.class)
                .checkedBy(spec ->
                        SmpSettings.checkMilestones(spec, "minecraft:on_ancient_city_maps"::equals, item -> true));
        store.checked("smp", accepted, Map.of());

        final Group<MilestonesSpec> rejected = Group.of("milestones", MilestonesSpec.class)
                .checkedBy(spec -> SmpSettings.checkMilestones(spec, key -> false, item -> true));
        assertRefused(rejected, Map.of(), "unknown structure tag minecraft:on_ancient_city_maps");
    }

    @Test
    void anEmptyKitListIsRefused() {
        final Exception refused =
                assertThrows(Exception.class, () -> store.checked("smp", DUELS, Map.of("duel-kits", List.of())));

        assertTrue(refused.getMessage().contains("duel-kits must not be empty"), refused.getMessage());
    }

    @Test
    void aFifthKitIsRefusedSinceTheDialogHasFourRows() {
        final List<Map<String, Object>> kits = IntStream.range(0, 5)
                .mapToObj(index -> Map.<String, Object>of("name", "Kit " + index))
                .toList();
        final Exception refused =
                assertThrows(Exception.class, () -> store.checked("smp", DUELS, Map.of("duel-kits", kits)));

        assertTrue(refused.getMessage().contains("more than 4"), refused.getMessage());
    }

    @Test
    void aStoredWheelBlockIsReadAsItsWorldAndCoordinates() throws Exception {
        final Map<String, Object> block = Map.of("world", "nordtal_nether", "x", -3, "y", 70, "z", 12);
        final WheelSpec wheel =
                store.checked("smp", Group.of("wheel", WheelSpec.class), Map.of("wheel-blocks", List.of(block)));

        final WheelBlockSpec read = wheel.wheelBlocks().getFirst();
        assertEquals(List.of("nordtal_nether", -3, 70, 12), List.of(read.world(), read.x(), read.y(), read.z()));
    }

    @Test
    void aProtectedAreaReadsNamed3dPolygons() throws Exception {
        final Map<String, Object> area = Map.of(
                "name",
                "Spawn",
                "world",
                "nordtal",
                "min-y",
                60,
                "max-y",
                80,
                "corners",
                List.of(
                        Map.of("x", 0, "z", 0),
                        Map.of("x", 10, "z", 0),
                        Map.of("x", 10, "z", 4),
                        Map.of("x", 0, "z", 4)));
        final SpawnSpec spawn = store.checked("smp", SPAWN, Map.of("protected-areas", List.of(area)));

        final PolygonArea polygon =
                ConfigAreas.ofSpawn(spawn.protectedAreas()).all().getFirst();
        assertEquals("Spawn", polygon.name());
        assertEquals("nordtal", polygon.world());
        assertEquals(60, polygon.minY());
        assertEquals(80, polygon.maxY());
        assertTrue(polygon.containsBlock("nordtal", 10, 80, 4));
        assertFalse(polygon.containsBlock("nordtal", 11, 70, 2));
    }

    @Test
    void aTrialOfAFifthKitNamesTheSettingAndTheRuleItBroke() {
        final List<Map<String, Object>> kits = IntStream.range(0, 5)
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
                Arrays.asList("duel-kits", SettingRule.MOST_ENTRIES, "4"),
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
                        DUELS,
                        Map.of("negotiation-area", List.of(Map.of("world", ""))),
                        "negotiation-area[0].world must not be empty"),
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
                () -> assertRefused(
                        DUELS, Map.of("arena", List.of(Map.of("world", ""))), "arena[0].world must not be empty"),
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
        final Map<String, Object> area = Map.of("world", "", "min-y", 0, "max-y", 10);
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

    @Test
    void theCustomBorderRefusesAnAreaOfTwoCornersAndAWorldTwice() {
        final Group<CustomBorderSpec> checked =
                CUSTOM_BORDER.checkedBy(spec -> SmpSettings.checkCustomBorder(spec, "nordtal"));
        final Map<String, Object> line =
                Map.of("name", "spawn", "corners", List.of(Map.of("x", 1, "z", 2), Map.of("x", 3, "z", 4)));
        final Map<String, Object> bowTie = Map.of(
                "name",
                "spawn",
                "corners",
                List.of(
                        Map.of("x", 0, "z", 0),
                        Map.of("x", 10, "z", 10),
                        Map.of("x", 10, "z", 0),
                        Map.of("x", 0, "z", 10)));
        final Map<String, Object> offTheMap = Map.of(
                "name",
                "spawn",
                "corners",
                List.of(Map.of("x", -1, "z", 0), Map.of("x", 10, "z", 0), Map.of("x", 10, "z", 10)));
        assertAll(
                () -> assertRefused(
                        checked,
                        Map.of("worlds", List.of(Map.of("world", "nordtal", "polygons", List.of(line)))),
                        "the area \"spawn\" needs at least 3 corners, has 2 in nordtal"),
                () -> assertRefused(
                        checked,
                        Map.of("worlds", List.of(Map.of("world", "nordtal", "polygons", List.of(bowTie)))),
                        "the edges of the area \"spawn\" cross or touch each other in nordtal"),
                () -> assertRefused(
                        checked,
                        Map.of("worlds", List.of(Map.of("world", "nordtal", "polygons", List.of(offTheMap)))),
                        "has a corner at -1, 0 outside the map from 0 to 10239 in nordtal"),
                () -> assertDoesNotThrow(() -> new MemorySettingStore()
                        .checked(
                                "smp",
                                checked,
                                Map.of("worlds", List.of(Map.of("world", "nether", "polygons", List.of(offTheMap)))))),
                () -> assertRefused(
                        checked,
                        Map.of("worlds", List.of(Map.of("world", "nordtal"), Map.of("world", "nordtal"))),
                        "holds nordtal twice"));
    }

    @Test
    void spawnAndDuel3dAreasRefuseMinYAboveMaxYAndBrokenPolygons() {
        final Group<SpawnSpec> checkedSpawn = SPAWN.checkedBy(spec -> SmpSettings.checkSpawn(spec, "nordtal"));
        final Group<DuelsSpec> checkedDuels = DUELS.checkedBy(spec -> SmpSettings.checkDuels(spec, "nordtal"));
        final List<Map<String, Object>> square = List.of(
                Map.of("x", 10, "z", 10), Map.of("x", 20, "z", 10), Map.of("x", 20, "z", 20), Map.of("x", 10, "z", 20));
        final Map<String, Object> inverted =
                Map.of("name", "Spawn", "world", "nordtal", "min-y", 90, "max-y", 60, "corners", square);
        final Map<String, Object> twoCorners = Map.of(
                "name",
                "Arena",
                "world",
                "nordtal",
                "min-y",
                60,
                "max-y",
                80,
                "corners",
                List.of(Map.of("x", 10, "z", 10), Map.of("x", 20, "z", 10)));
        assertAll(
                () -> assertRefused(
                        checkedSpawn,
                        Map.of("protected-areas", List.of(inverted)),
                        "has its bottom at 90, above its top at 60 in nordtal"),
                () -> assertRefused(
                        checkedDuels,
                        Map.of("arena", List.of(inverted)),
                        "has its bottom at 90, above its top at 60 in nordtal"),
                () -> assertRefused(
                        checkedDuels,
                        Map.of("negotiation-area", List.of(twoCorners)),
                        "needs at least 3 corners, has 2 in nordtal"));
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
                Specs.createDefault(DuelsSpec.class)
                        .arena()
                        .getFirst()
                        .corners()
                        .size(),
                duels.arena().getFirst().corners().size());
        final SpawnSpec written = Specs.createDefault(SpawnSpec.class);
        assertEquals(written.protectedAreas().size(), reread.protectedAreas().size());

        final SpotSpec spawn = reread.nordtalSpawn();
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

    /** A place in a world is drawn as one Position field, so every spec that holds a coordinate is marked as one. */
    @Test
    void everyPlaceInAWorldIsAPositionField() {
        final Map<String, Position.Shape> positions = new LinkedHashMap<>();
        final List<String> unmarked = new ArrayList<>();
        GROUPS.forEach(group -> collectPositions(group.spec(), group.name(), positions, unmarked));

        assertEquals(
                Map.ofEntries(
                        Map.entry("border.border-centre", Position.Shape.CENTRE),
                        Map.entry("custom-border.worlds.polygons.corners", Position.Shape.CENTRE),
                        Map.entry("wheel.wheel-blocks", Position.Shape.BLOCK),
                        Map.entry("duels.negotiation-area.corners", Position.Shape.CENTRE),
                        Map.entry("duels.arena.corners", Position.Shape.CENTRE),
                        Map.entry("duels.fighter-start-first", Position.Shape.SPOT),
                        Map.entry("duels.fighter-start-second", Position.Shape.SPOT),
                        Map.entry("duels.exit", Position.Shape.SPOT),
                        Map.entry("boards.boards", Position.Shape.SPOT),
                        Map.entry("spawn.nordtal-spawn", Position.Shape.SPOT),
                        Map.entry("spawn.protected-areas.corners", Position.Shape.CENTRE),
                        Map.entry("rails.spawn-station", Position.Shape.BLOCK)),
                positions);
        assertTrue(unmarked.isEmpty(), "a spec holds coordinates but is no Position field: " + unmarked);
    }

    private static void collectPositions(
            final Class<?> spec,
            final String path,
            final Map<String, Position.Shape> positions,
            final List<String> unmarked) {
        for (final SpecProperty property : Specs.from(spec).properties().values()) {
            if (property.isHandledByProxy()) {
                continue;
            }
            final Method getter = property.getter();
            final String at = path + "." + property.key();
            final Position position = getter.getAnnotation(Position.class);
            for (final Class<?> nested : specTypesOf(getter.getGenericReturnType())) {
                final Set<String> keys = Specs.from(nested).properties().values().stream()
                        .map(SpecProperty::key)
                        .collect(Collectors.toSet());
                if (position != null) {
                    positions.put(at, position.value());
                } else if (keys.contains("x") || keys.contains("min-x")) {
                    unmarked.add(at);
                } else {
                    collectPositions(nested, at, positions, unmarked);
                }
            }
        }
    }
}
