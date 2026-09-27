package eu.nordtal.s2.smp.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where the database's progress and the file's definition meet. */
class SeasonStateTest {

    private static final MilestoneTrack TRACK = new MilestoneTrack(List.of(
            milestone("opening", Unlock.BORDER, 43),
            milestone("settling", Unlock.BORDER, 99),
            milestone("the-nether", Unlock.NETHER, 0),
            milestone("expansion", Unlock.BORDER, 400),
            milestone("the-end", Unlock.END, 0),
            milestone("the-last-one", Unlock.NOTHING, 0)));

    private static Milestone milestone(final String key, final Unlock unlock, final int border) {
        return new Milestone(key, unlock, border, 100, false, List.of());
    }

    @Test
    void aFreshStateIsUnreadAndNotFinished() {
        // Both have no key and no objectives. Only the first may be announced as "finished".
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
        assertEquals(0, state.borderDiameter(), "an untouched border is 0 and not a guess at 20");
        assertFalse(state.isUnlocked(Unlock.NETHER));
    }

    @Test
    void theBorderIsTheLargestAnyCompletedMilestoneAsked() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("opening", "settling"), TRACK);

        assertEquals(99, state.borderDiameter());
    }

    /** An admin completing a milestone out of order must not shrink the world on the next restart. */
    @Test
    void anOutOfOrderCompletionCannotShrinkTheWorld() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("expansion", "opening"), TRACK);

        assertEquals(400, state.borderDiameter());
    }

    @Test
    void unlocksAreIndependentOfEachOther() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("the-nether"), TRACK);

        assertTrue(state.isUnlocked(Unlock.NETHER));
        assertFalse(state.isUnlocked(Unlock.END), "the End is its own milestone");
    }

    /** A key in the database that the file no longer declares contributes nothing rather than throwing. */
    @Test
    void aCompletedKeyTheTrackNoLongerDeclaresIsIgnored() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("opening", "a-milestone-that-was-deleted"), TRACK);

        assertEquals(43, state.borderDiameter());
        assertEquals(2, state.completedKeys().size(), "the raw keys are still reported as stored");
    }

    @Test
    void aMilestoneThatUnlocksNothingChangesNothing() {
        final SeasonState state = new SeasonState();
        state.refresh(List.of("the-last-one"), TRACK);

        assertEquals(0, state.borderDiameter());
        assertTrue(state.unlocked().contains(Unlock.NOTHING));
        assertFalse(state.isUnlocked(Unlock.NETHER));
    }
}
