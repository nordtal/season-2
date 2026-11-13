package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import eu.nordtal.season.smp.milestone.Unlock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmptyObjectivesTest {

    private static final Milestone OPENING = new Milestone("opening", Unlock.BORDER, 43, true, List.of());
    private static final Milestone FOOTHOLD = new Milestone("foothold", Unlock.BORDER, 99, false, List.of(gate()));
    private static final Milestone LAST = new Milestone("last", Unlock.NOTHING, 0, true, List.of());
    private static final MilestoneTrack TRACK = new MilestoneTrack(List.of(OPENING, FOOTHOLD, LAST));

    private static Objective gate() {
        return new Objective(
                "gate", ObjectiveType.ADVANCEMENT, "gate", 3, List.of(), "", List.of(), "minecraft:story/root", 0, 0);
    }

    @Test
    void beforeTheFirstUnlockTheSeasonHasNotStarted() {
        assertEquals(
                Optional.of(ObjectiveDialog.Empty.NOT_STARTED), ObjectiveDialog.Empty.of(TRACK, List.of(), OPENING));
    }

    @Test
    void aMilestoneWithObjectivesShowsThem() {
        assertEquals(Optional.empty(), ObjectiveDialog.Empty.of(TRACK, List.of("opening"), FOOTHOLD));
    }

    @Test
    void aLaterMilestoneWithoutObjectivesShowsNone() {
        assertEquals(
                Optional.of(ObjectiveDialog.Empty.NONE),
                ObjectiveDialog.Empty.of(TRACK, List.of("opening", "foothold"), LAST));
    }

    @Test
    void nothingActiveBeforeAnyUnlockHasNotStarted() {
        assertEquals(Optional.of(ObjectiveDialog.Empty.NOT_STARTED), ObjectiveDialog.Empty.of(TRACK, List.of(), null));
    }

    @Test
    void aFinishedTrackShowsAll() {
        assertEquals(Optional.of(ObjectiveDialog.Empty.ALL), ObjectiveDialog.Empty.of(TRACK, TRACK.keys(), null));
    }
}
