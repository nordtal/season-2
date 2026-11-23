package eu.nordtal.season.smp.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Unlock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeasonStateTest {

    private static final MilestoneTrack TRACK = new MilestoneTrack(List.of(
            milestone("opening", Unlock.BORDER, List.of("departure")),
            milestone("settling", Unlock.BORDER, List.of("foothold", "harbour")),
            milestone("the-nether", Unlock.NETHER, List.of()),
            milestone("expansion", Unlock.BORDER, List.of("settlement")),
            milestone("the-end", Unlock.END, List.of()),
            milestone("the-last-one", Unlock.NOTHING, List.of())));

    private static Milestone milestone(final String key, final Unlock unlock, final List<String> regions) {
        return new Milestone(key, unlock, regions, false, List.of());
    }

    @Test
    void aFreshStateIsUnreadAndNotFinished() {
        // Both milestones have no key and no objectives, and only the first one counts as finished.
        final SeasonState state = new SeasonState();
        assertTrue(state.active().unread());

        state.refreshActive(null, List.of());
        assertFalse(state.active().unread(), "a refresh that finds nothing active means the track is done");
        assertFalse(SeasonState.Active.NONE.unread());
    }

    @Test
    void nothingIsUnlockedOnAnEmptySeason() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of(), TRACK);

        assertTrue(state.unlocked().isEmpty());
        assertEquals(Set.of(), state.borderRegions(), "the first milestone's border waits for its unlock");
        assertFalse(state.isUnlocked(Unlock.NETHER));
    }

    @Test
    void theOpenRegionsAreTheUnionOfCompletedBorderMilestones() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("opening", "settling"), TRACK);

        assertEquals(Set.of("departure", "foothold", "harbour"), state.borderRegions());
    }

    /** An admin completing a milestone out of order still opens every completed milestone's regions. */
    @Test
    void anOutOfOrderCompletionOpensBothRegions() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("expansion", "opening"), TRACK);

        assertEquals(Set.of("settlement", "departure"), state.borderRegions());
    }

    @Test
    void unlocksAreIndependentOfEachOther() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("the-nether"), TRACK);

        assertTrue(state.isUnlocked(Unlock.NETHER));
        assertFalse(state.isUnlocked(Unlock.END), "the End is its own milestone");
    }

    /** A key in the database that the file no longer declares adds nothing and throws nothing. */
    @Test
    void aCompletedKeyTheTrackNoLongerDeclaresIsIgnored() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("opening", "a-milestone-that-was-deleted"), TRACK);

        assertEquals(Set.of("departure"), state.borderRegions());
        assertEquals(2, state.completedKeys().size(), "the raw keys are still reported as stored");
    }

    @Test
    void theNextMilestoneIsTheFirstNotCompleted() {
        assertEquals("opening", TRACK.next(List.of()).orElseThrow().key());
        assertEquals(
                "the-nether",
                TRACK.next(List.of("opening", "settling")).orElseThrow().key());
        assertEquals(
                "settling",
                TRACK.next(List.of("opening", "the-nether")).orElseThrow().key());
        assertTrue(TRACK.next(TRACK.keys()).isEmpty(), "a finished track has nothing to activate");
    }

    @Test
    void aMilestoneThatUnlocksNothingChangesNothing() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("the-last-one"), TRACK);

        assertEquals(Set.of(), state.borderRegions(), "no border is unlocked");
        assertTrue(state.unlocked().contains(Unlock.NOTHING));
        assertFalse(state.isUnlocked(Unlock.NETHER));
    }
}
