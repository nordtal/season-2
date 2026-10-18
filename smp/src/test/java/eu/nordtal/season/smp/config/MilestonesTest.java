package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import eu.nordtal.season.smp.milestone.Unlock;
import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MilestonesTest {

    private static final Group<MilestonesSpec> MILESTONES =
            Group.of("milestones", MilestonesSpec.class).checkedBy(SmpSettings::checkMilestones);

    private final MemorySettingStore store = new MemorySettingStore();

    private final Map<String, Object> values = new LinkedHashMap<>();

    private MilestonesSpec load() throws SettingsException {
        return store.checked("smp", MILESTONES, values);
    }

    @Test
    void theStoredTrackReadsBackAsTheWholeTrack() throws Exception {
        final MilestonesSpec written = load();

        values.put("milestones", Specs.gsonBuilder().create().toJson(written.milestones()));
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
        assertEquals(Unlock.BORDER, foothold.unlock());
        assertEquals(99, foothold.borderDiameter());

        // The item list of a HAND_IN is the deepest value in the file.
        final var logs = foothold.objective("logs").orElseThrow();
        assertEquals(ObjectiveType.HAND_IN, logs.type());
        assertEquals(2048L, logs.target());
        assertEquals(30, logs.auraBudget());
        assertEquals(20, logs.spinBudget());
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

        assertEquals(
                List.of(
                        List.of(),
                        List.of(),
                        List.of(List.of(30, 20)),
                        List.of(List.of(60, 20)),
                        List.of(List.of(80, 16)),
                        List.of(List.of(80, 16)),
                        List.of(List.of(110, 12)),
                        List.of(List.of(170, 10))),
                track.milestones().stream()
                        .map(milestone -> milestone.objectives().stream()
                                .map(objective -> List.of(objective.auraBudget(), objective.spinBudget()))
                                .distinct()
                                .toList())
                        .toList());
    }

    @Test
    void everyMilestoneWithObjectivesCarriesExactlyOneParticipationGate() throws Exception {
        final MilestoneTrack track = Milestones.read(load()).track();

        // ADVANCEMENT is the only type that counts distinct players, so three players cannot finish it alone.
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
        // This is the easiest change that makes the whole track soloable, and no other test would notice it.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
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
        // An objective that nothing can be handed in for makes the milestone impossible to unlock.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
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
    void aNegativeSpinBudgetStopsTheLoad() throws Exception {
        // A negative budget would take spins away from every player who worked on the objective.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
                    admin-unlocked: false
                    objectives:
                      - key: gate
                        type: ADVANCEMENT
                        role: participation
                        target: 10
                        aura-budget: 30
                        spin-budget: -1
                        items: []
                        statistic: ''
                        subjects: []
                        advancement: 'minecraft:story/iron_tools'
                """);

        final SettingsException error = assertThrows(SettingsException.class, () -> load());
        assertTrue(error.getMessage().contains("negative aura or spin budget"), error.getMessage());
    }

    @Test
    void aLeftoverFieldFromAnotherTypeStopsTheLoad() throws Exception {
        // A half-finished type change, where HAND_IN became STATISTIC and the item list stayed, counts nothing.
        writeTrack("""
                milestones:
                  - key: foothold
                    unlocks: BORDER
                    border-diameter: 99
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

    private void writeTrack(final String yaml) {
        final Map<String, Object> parsed = new Yaml().load(yaml);
        parsed.forEach(
                (key, value) -> values.put(key, Specs.gsonBuilder().create().toJson(value)));
    }
}
