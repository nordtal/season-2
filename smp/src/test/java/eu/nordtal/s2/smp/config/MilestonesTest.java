package eu.nordtal.s2.smp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** That the milestone track is stored as one value and read back whole, two levels of nesting deep. */
class MilestonesTest {

    private static final Group<MilestonesSpec> MILESTONES =
            Group.of("milestones", MilestonesSpec.class).checkedBy(SmpSettings::checkMilestones);

    private final MemorySettingStore store = new MemorySettingStore();

    /** What an admin stored, taken by the next load. */
    private final Map<String, Object> values = new LinkedHashMap<>();

    private MilestonesSpec load() throws SettingsException {
        return store.checked("smp", MILESTONES, values);
    }

    @Test
    void theStoredTrackReadsBackAsTheWholeTrack() throws Exception {
        final MilestonesSpec written = load();

        // A SECOND load, over the whole track stored as the one value it is.
        values.put("milestones", ConfigLoader.gsonBuilder().create().toJson(written.milestones()));
        final MilestonesSpec reread = load();
        final MilestoneTrack track = Milestones.read(reread).track();

        assertEquals(
                List.of("waiting", "departure", "foothold", "settlement", "nether", "end", "expanse", "frontier"),
                track.keys());
        assertEquals(written.milestones().size(), reread.milestones().size());
    }

    @Test
    void theNestedObjectivesSurviveTheRoundTrip() throws Exception {
        final MilestoneTrack track = Milestones.read(load()).track();

        final var foothold = track.milestone("foothold").orElseThrow();
        assertEquals(4, foothold.objectives().size());
        assertEquals(30, foothold.objectivePot());
        assertEquals(Unlock.BORDER, foothold.unlock());
        assertEquals(99, foothold.borderDiameter());

        // A HAND_IN's item list is the deepest thing in the file: strings, in an objective, in a milestone, in a list.
        final var logs = foothold.objective("logs").orElseThrow();
        assertEquals(ObjectiveType.HAND_IN, logs.type());
        assertEquals(2048L, logs.target());
        assertTrue(logs.items().contains("minecraft:oak_log"), "the item list came back as " + logs.items());
        assertEquals(9, logs.items().size());

        final var coal = foothold.objective("coal").orElseThrow();
        assertEquals(ObjectiveType.STATISTIC, coal.type());
        assertEquals("minecraft:mine_block", coal.statistic());
        assertEquals(List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), coal.subjects());
        assertTrue(coal.items().isEmpty());
    }

    @Test
    void theTrackMatchesTheTableInTheConcept() throws Exception {
        final MilestoneTrack track = Milestones.read(load()).track();

        // The track, column by column. The numbers are allowed to change; this is what makes a retune deliberate.
        assertEquals(20, track.milestone("waiting").orElseThrow().borderDiameter());
        assertEquals(43, track.milestone("departure").orElseThrow().borderDiameter());
        assertEquals(400, track.milestone("settlement").orElseThrow().borderDiameter());
        assertEquals(900, track.milestone("expanse").orElseThrow().borderDiameter());
        assertEquals(4000, track.milestone("frontier").orElseThrow().borderDiameter());

        assertEquals(Unlock.NETHER, track.milestone("nether").orElseThrow().unlock());
        assertEquals(Unlock.END, track.milestone("end").orElseThrow().unlock());
        assertEquals(
                0,
                track.milestone("nether").orElseThrow().borderDiameter(),
                "the Nether and the End carry no border step - the dimension is the reward");

        assertEquals(30, track.milestone("foothold").orElseThrow().objectivePot());
        assertEquals(60, track.milestone("settlement").orElseThrow().objectivePot());
        assertEquals(80, track.milestone("nether").orElseThrow().objectivePot());
        assertEquals(80, track.milestone("end").orElseThrow().objectivePot());
        assertEquals(110, track.milestone("expanse").orElseThrow().objectivePot());
        assertEquals(170, track.milestone("frontier").orElseThrow().objectivePot());
    }

    @Test
    void everyMilestoneWithObjectivesCarriesExactlyOneParticipationGate() throws Exception {
        final MilestoneTrack track = Milestones.read(load()).track();

        // ADVANCEMENT is the only type counting distinct players, the only one three people cannot finish alone.
        assertEquals(
                List.of(10L, 10L, 8L, 8L, 6L, 5L),
                track.milestones().stream()
                        .filter(milestone -> !milestone.hasNoObjectives())
                        .map(milestone -> milestone.objectives().stream()
                                .filter(objective -> objective.isParticipationGate())
                                .findFirst()
                                .orElseThrow()
                                .target())
                        .toList());
    }

    @Test
    void theOpeningTwoMilestonesHaveNothingToFinish() throws Exception {
        final MilestoneTrack track = Milestones.read(load()).track();

        assertTrue(track.milestone("waiting").orElseThrow().hasNoObjectives());
        assertTrue(track.milestone("departure").orElseThrow().hasNoObjectives());
        assertFalse(track.milestone("waiting").orElseThrow().adminUnlocked());
        assertTrue(
                track.milestone("departure").orElseThrow().adminUnlocked(),
                "departure is the one milestone an admin opens, at the season's opening");
    }

    @Test
    void anObjectiveWithATypeThatDoesNotExistStopsTheLoad() throws Exception {
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
                    objective-pot: 30
                    admin-unlocked: false
                    objectives:
                      - key: logs
                        type: HANDIN
                        role: gathering
                        target: 2048
                        items: [OAK_LOG]
                        statistic: ''
                        subjects: []
                        advancement: ''
                """);

        final SettingsException error = assertThrows(SettingsException.class, () -> load());
        assertTrue(error.getMessage().contains("HANDIN"), error.getMessage());
    }

    @Test
    void aMilestoneWithNoParticipationGateStopsTheLoad() throws Exception {
        // The easiest way to make the whole track soloable, and nothing else would notice.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
                    objective-pot: 30
                    admin-unlocked: false
                    objectives:
                      - key: logs
                        type: HAND_IN
                        role: gathering
                        target: 2048
                        items: [OAK_LOG]
                        statistic: ''
                        subjects: []
                        advancement: ''
                """);

        final SettingsException error = assertThrows(SettingsException.class, () -> load());
        assertTrue(error.getMessage().contains("participation gate"), error.getMessage());
    }

    @Test
    void aHandInWithNoItemsStopsTheLoad() throws Exception {
        // An objective nothing can be handed in for is a milestone that could never unlock.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
                    objective-pot: 30
                    admin-unlocked: false
                    objectives:
                      - key: logs
                        type: HAND_IN
                        role: gathering
                        target: 2048
                        items: []
                        statistic: ''
                        subjects: []
                        advancement: ''
                      - key: gate
                        type: ADVANCEMENT
                        role: participation
                        target: 10
                        items: []
                        statistic: ''
                        subjects: []
                        advancement: 'minecraft:story/iron_tools'
                """);

        final SettingsException error = assertThrows(SettingsException.class, () -> load());
        assertTrue(error.getMessage().contains("HAND_IN with no items"), error.getMessage());
    }

    @Test
    void aLeftoverFieldFromAnotherTypeStopsTheLoad() throws Exception {
        // A half-finished type change: HAND_IN became STATISTIC and the item list stayed, silently counting nothing.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
                    objective-pot: 30
                    admin-unlocked: false
                    objectives:
                      - key: coal
                        type: STATISTIC
                        role: mining
                        target: 1500
                        items: [COAL_ORE]
                        statistic: 'MINE_BLOCK'
                        subjects: [COAL_ORE]
                        advancement: ''
                      - key: gate
                        type: ADVANCEMENT
                        role: participation
                        target: 10
                        items: []
                        statistic: ''
                        subjects: []
                        advancement: 'minecraft:story/iron_tools'
                """);

        final SettingsException error = assertThrows(SettingsException.class, () -> load());
        assertTrue(error.getMessage().contains("belongs to"), error.getMessage());
    }

    /** Stores every top-level value of {@code yaml} as Steward would, the track as one JSON value. */
    private void writeTrack(final String yaml) {
        final Map<String, Object> parsed = new Yaml().load(yaml);
        parsed.forEach((key, value) ->
                values.put(key, ConfigLoader.gsonBuilder().create().toJson(value)));
    }
}
