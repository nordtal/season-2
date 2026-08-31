package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.exception.ConfigValidationException;

import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.Unlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code milestones.yml} can actually be written and read back, and that what comes back is
 * the track docs/smp.md#the-track describes.
 *
 * <h2>What this proves that nothing else can</h2>
 * <b>Two levels of nesting through jcore.</b> A list of milestones each holding a list of
 * objectives is one level deeper than anything in this repository had used - {@code discord-bot}'s
 * tier list is a flat {@code List<NestedSpec>} - and jcore's config system is a vendored copy of an
 * unmaintained library. Whether its writer serialises a nested list and its reader gives it back is
 * a fact about that library, and the only honest way to know it is to do it. The alternative format
 * (a flat list of objectives each naming its milestone) was rejected on readability, and this test
 * is what stops that rejection from being a guess.
 */
class MilestonesTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MilestonesTest.class);

    @TempDir
    Path directory;

    @Test
    void aFreshFileIsWrittenAndReadsBackAsTheWholeTrack() throws Exception {
        final MilestonesSpec written = Configs.milestones(directory, LOGGER).get();

        assertTrue(Files.isRegularFile(directory.resolve("milestones.yml")),
                "a fresh load has to write the defaults out, or there is nothing to edit");

        // Read it back through a SECOND load, from the file this one just wrote. The first handle
        // still holds the in-memory defaults, so asserting against it would prove nothing about
        // what went through YAML.
        final MilestonesSpec reread = Configs.milestones(directory, LOGGER).get();
        final MilestoneTrack track = Milestones.read(reread).track();

        assertEquals(List.of("waiting", "departure", "foothold", "settlement", "nether", "end",
                "expanse", "frontier"), track.keys());
        assertEquals(written.milestones().size(), reread.milestones().size());
    }

    @Test
    void theNestedObjectivesSurviveTheRoundTrip() throws Exception {
        Configs.milestones(directory, LOGGER);
        final MilestoneTrack track = Milestones.read(Configs.milestones(directory, LOGGER).get()).track();

        final var foothold = track.milestone("foothold").orElseThrow();
        assertEquals(4, foothold.objectives().size());
        assertEquals(30, foothold.objectivePot());
        assertEquals(Unlock.BORDER, foothold.unlock());
        assertEquals(99, foothold.borderDiameter());

        // A HAND_IN's item list is the deepest thing in the file: a list of strings, inside an
        // objective, inside a milestone, inside a list. If nesting were going to fail anywhere it
        // would fail here.
        final var logs = foothold.objective("logs").orElseThrow();
        assertEquals(ObjectiveType.HAND_IN, logs.type());
        assertEquals(2048L, logs.target());
        assertTrue(logs.items().contains("OAK_LOG"), "the item list came back as " + logs.items());
        assertEquals(9, logs.items().size());

        final var coal = foothold.objective("coal").orElseThrow();
        assertEquals(ObjectiveType.STATISTIC, coal.type());
        assertEquals("MINE_BLOCK", coal.statistic());
        assertEquals(List.of("COAL_ORE", "DEEPSLATE_COAL_ORE"), coal.subjects());
        assertTrue(coal.items().isEmpty());
    }

    @Test
    void theTrackMatchesTheTableInTheConcept() throws Exception {
        final MilestoneTrack track = Milestones.read(Configs.milestones(directory, LOGGER).get()).track();

        // docs/smp.md#the-track, column by column. If somebody retunes the defaults this test is
        // what tells them the document is now out of date - which is the point: the numbers are
        // allowed to change, the two just have to change together.
        assertEquals(20, track.milestone("waiting").orElseThrow().borderDiameter());
        assertEquals(43, track.milestone("departure").orElseThrow().borderDiameter());
        assertEquals(400, track.milestone("settlement").orElseThrow().borderDiameter());
        assertEquals(900, track.milestone("expanse").orElseThrow().borderDiameter());
        assertEquals(4000, track.milestone("frontier").orElseThrow().borderDiameter());

        assertEquals(Unlock.NETHER, track.milestone("nether").orElseThrow().unlock());
        assertEquals(Unlock.END, track.milestone("end").orElseThrow().unlock());
        assertEquals(0, track.milestone("nether").orElseThrow().borderDiameter(),
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
        final MilestoneTrack track = Milestones.read(Configs.milestones(directory, LOGGER).get()).track();

        // The rule the whole track's difficulty rests on: the ADVANCEMENT objective is the only type
        // that counts distinct players, so it is the only one three industrious people cannot finish
        // alone. The gate counts fall across the track because the population does.
        assertEquals(List.of(10L, 10L, 8L, 8L, 6L, 5L), track.milestones().stream()
                .filter(milestone -> !milestone.hasNoObjectives())
                .map(milestone -> milestone.objectives().stream()
                        .filter(objective -> objective.isParticipationGate())
                        .findFirst().orElseThrow().target())
                .toList());
    }

    @Test
    void theOpeningTwoMilestonesHaveNothingToFinish() throws Exception {
        final MilestoneTrack track = Milestones.read(Configs.milestones(directory, LOGGER).get()).track();

        assertTrue(track.milestone("waiting").orElseThrow().hasNoObjectives());
        assertTrue(track.milestone("departure").orElseThrow().hasNoObjectives());
        assertFalse(track.milestone("waiting").orElseThrow().adminUnlocked());
        assertTrue(track.milestone("departure").orElseThrow().adminUnlocked(),
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

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.milestones(directory, LOGGER));
        assertTrue(error.getMessage().contains("HANDIN"), error.getMessage());
    }

    @Test
    void aMilestoneWithNoParticipationGateStopsTheLoad() throws Exception {
        // The single easiest way to make the whole track soloable, and nothing else would notice.
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

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.milestones(directory, LOGGER));
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

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.milestones(directory, LOGGER));
        assertTrue(error.getMessage().contains("HAND_IN with no items"), error.getMessage());
    }

    @Test
    void aLeftoverFieldFromAnotherTypeStopsTheLoad() throws Exception {
        // What a half-finished type change looks like: somebody changed HAND_IN to STATISTIC and
        // left the item list behind. Ignoring it would leave an objective that silently counts
        // nothing.
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

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.milestones(directory, LOGGER));
        assertTrue(error.getMessage().contains("belongs to"), error.getMessage());
    }

    private void writeTrack(final String yaml) throws Exception {
        Files.writeString(directory.resolve("milestones.yml"), yaml);
    }
}
