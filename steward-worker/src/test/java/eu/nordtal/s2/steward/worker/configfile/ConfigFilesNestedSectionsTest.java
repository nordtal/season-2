package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SettingKind;
import eu.nordtal.jcore.config.schema.SettingType;
import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A list of sections whose sections hold lists of their own - smp's {@code milestones.yml}, where
 * every milestone carries a list of objectives and every objective a list of items.
 *
 * <p>The cards are built from the schema, not from a guess at how uniform the file looks, so the
 * card shape reaches every level the schema describes, and the writer edits, appends and removes
 * at any of those levels while leaving every other line where it was.</p>
 */
class ConfigFilesNestedSectionsTest {

    @TempDir
    Path directory;

    private Path file;

    private static final Gson GSON = new Gson();

    private static final String TRACK = "# the milestone track\n"
            + "milestones:\n"
            + "- key: waiting\n"
            + "  unlocks: BORDER\n"
            + "  objectives: []\n"
            + "- key: foothold\n"
            + "  unlocks: BORDER\n"
            + "  objectives:\n"
            + "  - key: logs\n"
            + "    type: HAND_IN\n"
            + "    # lowering this is always allowed\n"
            + "    target: 2048\n"
            + "    items:\n"
            + "    - OAK_LOG\n"
            + "    - SPRUCE_LOG\n"
            + "  - key: coal\n"
            + "    type: STATISTIC\n"
            + "    target: 1500\n"
            + "    items: []\n"
            + "- key: nether\n"
            + "  unlocks: NETHER\n"
            + "  objectives:\n"
            + "  - key: blaze\n"
            + "    type: STATISTIC\n"
            + "    target: 30\n"
            + "    items: []\n"
            + "season: 2\n";

    @BeforeEach
    void writeTrack() throws IOException {
        file = directory.resolve("milestones.yml");
        Files.writeString(file, TRACK);
        final Map<String, SchemaNode> objective = new LinkedHashMap<>();
        objective.put("key", scalar(SettingType.STRING));
        objective.put("type", scalar(SettingType.STRING));
        objective.put("target", scalar(SettingType.INTEGER));
        objective.put(
                "items",
                new SchemaNode(SettingKind.LIST, "Items", "", false, false, SettingType.STRING, null, Map.of(), null));
        final Map<String, SchemaNode> milestone = new LinkedHashMap<>();
        milestone.put("key", scalar(SettingType.STRING));
        milestone.put("unlocks", scalar(SettingType.STRING));
        milestone.put("objectives", list("Objectives", objective));
        final Map<String, SchemaNode> root = new LinkedHashMap<>();
        root.put("milestones", list("Milestones", milestone));
        root.put("season", scalar(SettingType.INTEGER));
        Files.writeString(
                directory.resolve("milestones.schema.json"),
                GSON.toJson(new SchemaNode(SettingKind.MAP, "", "", false, false, null, null, root, null)));
    }

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    @Test
    void theTemplateReachesEveryLevelTheSchemaDescribes() throws IOException {
        final ConfigEntry milestones = milestones();

        final ConfigEntry objectives = field(milestones.template(), "objectives");
        assertEquals(Kind.SECTIONS, objectives.kind());
        assertEquals(
                List.of("key", "type", "target", "items"),
                objectives.template().stream().map(ConfigEntry::key).toList());
        assertEquals(Kind.LIST, field(objectives.template(), "items").kind());
    }

    @Test
    void anEmptyListTheSchemaCallsSectionsIsSections() throws IOException {
        final ConfigEntry waitingObjectives = field(milestones().sections().getFirst(), "objectives");

        assertEquals(Kind.SECTIONS, waitingObjectives.kind());
        assertEquals(List.of(), waitingObjectives.sections());
        assertEquals(4, waitingObjectives.template().size());
    }

    // -----------------------------------------------------------------------------------------
    // Editing inside a nested entry
    // -----------------------------------------------------------------------------------------

    @Test
    void changingANestedValueTouchesThatLineOnly() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        objective(track, 1, 0).put("target", "1024");

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(TRACK.replace("target: 2048", "target: 1024"), Files.readString(file));
    }

    @Test
    void changingANestedListRewritesThatListOnly() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        objective(track, 1, 0).put("items", List.of("OAK_LOG", "BIRCH_LOG", "CHERRY_LOG"));

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(
                TRACK.replace("    - SPRUCE_LOG\n", "    - BIRCH_LOG\n    - CHERRY_LOG\n"), Files.readString(file));
    }

    // -----------------------------------------------------------------------------------------
    // Adding and removing a nested entry
    // -----------------------------------------------------------------------------------------

    @Test
    void anObjectiveAppendedToAMilestoneLandsUnderItsSiblings() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        objectives(track, 1).add(objectiveRow("iron", "HAND_IN", "64", List.of("IRON_INGOT")));

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(
                TRACK.replace(
                        "    items: []\n- key: nether",
                        "    items: []\n"
                                + "  - key: iron\n"
                                + "    type: HAND_IN\n"
                                + "    target: 64\n"
                                + "    items:\n"
                                + "    - IRON_INGOT\n"
                                + "- key: nether"),
                Files.readString(file));
    }

    @Test
    void theFirstObjectiveOfAMilestoneTurnsItsEmptyListIntoABlock() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        objectives(track, 0).add(objectiveRow("wood", "HAND_IN", "10", List.of()));

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(
                TRACK.replace(
                        "  objectives: []\n- key: foothold",
                        "  objectives:\n"
                                + "  - key: wood\n"
                                + "    type: HAND_IN\n"
                                + "    target: 10\n"
                                + "    items: []\n"
                                + "- key: foothold"),
                Files.readString(file));
    }

    @Test
    void removingAnObjectiveTakesItsItemsAndItsCommentWithIt() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        objectives(track, 1).removeFirst();

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(
                TRACK.replace(
                        "  - key: logs\n"
                                + "    type: HAND_IN\n"
                                + "    # lowering this is always allowed\n"
                                + "    target: 2048\n"
                                + "    items:\n"
                                + "    - OAK_LOG\n"
                                + "    - SPRUCE_LOG\n",
                        ""),
                Files.readString(file));
    }

    @Test
    void removingTheOnlyObjectiveLeavesAnEmptyListAndNotANull() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        objectives(track, 2).clear();

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(
                TRACK.replace(
                        "  objectives:\n"
                                + "  - key: blaze\n"
                                + "    type: STATISTIC\n"
                                + "    target: 30\n"
                                + "    items: []\n",
                        "  objectives: []\n"),
                Files.readString(file));
    }

    @Test
    void removingAMilestoneTakesEveryObjectiveUnderItAndNothingOfTheNext() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        track.remove(1);

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        final String before = TRACK.substring(0, TRACK.indexOf("- key: foothold"));
        final String after = TRACK.substring(TRACK.indexOf("- key: nether"));
        assertEquals(before + after, Files.readString(file));
    }

    @Test
    void aNewMilestoneCarriesItsObjectivesInTheFilesOwnIndentation() throws IOException {
        final List<Map<String, Object>> track = valuesOf(milestones());
        final Map<String, Object> end = new LinkedHashMap<>();
        end.put("key", "end");
        end.put("unlocks", "END");
        end.put(
                "objectives",
                new ArrayList<>(List.of(
                        objectiveRow("dragon", "ADVANCEMENT", "5", List.of()),
                        objectiveRow("pearls", "HAND_IN", "16", List.of("ENDER_PEARL")))));
        track.add(end);

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(
                TRACK.replace(
                        "season: 2\n",
                        "- key: end\n"
                                + "  unlocks: END\n"
                                + "  objectives:\n"
                                + "  - key: dragon\n"
                                + "    type: ADVANCEMENT\n"
                                + "    target: 5\n"
                                + "    items: []\n"
                                + "  - key: pearls\n"
                                + "    type: HAND_IN\n"
                                + "    target: 16\n"
                                + "    items:\n"
                                + "    - ENDER_PEARL\n"
                                + "season: 2\n"),
                Files.readString(file));
        assertEquals(track, valuesOf(milestones()));
    }

    @Test
    void anEditInOneMilestoneAndAnAppendInAnotherAreOneSave() throws IOException {
        // Pure append or pure remove is a rule per list, not per file: two different lists, one
        // changed in place and the other grown by one, are two unrelated edits that happen to
        // share a save button.
        final List<Map<String, Object>> track = valuesOf(milestones());
        objective(track, 1, 1).put("target", "1200");
        objectives(track, 2).add(objectiveRow("ghast", "STATISTIC", "3", List.of()));

        ConfigFiles.write(file, Map.of("milestones", ConfigChange.sections(track)));

        assertEquals(track, valuesOf(milestones()));
        assertTrue(Files.readString(file).startsWith("# the milestone track\n"));
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private ConfigEntry milestones() throws IOException {
        return ConfigFiles.read(file).find("milestones").orElseThrow();
    }

    private static ConfigEntry field(final List<ConfigEntry> fields, final String key) {
        return fields.stream()
                .filter(f -> f.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + key));
    }

    /** What a form would send back unchanged: every level as plain, mutable values. */
    private static List<Map<String, Object>> valuesOf(final ConfigEntry sections) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final List<ConfigEntry> section : sections.sections()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            for (final ConfigEntry field : section) {
                row.put(
                        field.key(),
                        switch (field.kind()) {
                            case SECTIONS -> valuesOf(field);
                            case LIST -> new ArrayList<>(field.items());
                            case SCALAR, MAP -> field.value();
                        });
            }
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectives(final List<Map<String, Object>> track, final int milestone) {
        return (List<Map<String, Object>>) track.get(milestone).get("objectives");
    }

    private static Map<String, Object> objective(
            final List<Map<String, Object>> track, final int milestone, final int index) {
        return objectives(track, milestone).get(index);
    }

    private static Map<String, Object> objectiveRow(
            final String key, final String type, final String target, final List<String> items) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("type", type);
        row.put("target", target);
        row.put("items", new ArrayList<>(items));
        return row;
    }

    private static SchemaNode scalar(final SettingType type) {
        return new SchemaNode(SettingKind.SCALAR, "", "", false, false, type, null, Map.of(), null);
    }

    private static SchemaNode list(final String label, final Map<String, SchemaNode> element) {
        return new SchemaNode(SettingKind.LIST, label, "", false, false, null, null, element, null);
    }
}
