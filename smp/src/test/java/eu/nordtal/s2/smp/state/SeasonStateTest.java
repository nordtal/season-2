package eu.nordtal.s2.smp.state;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the database's progress and the file's definition meet.
 *
 * <p>The database stores which milestone keys are finished; what each of them <em>unlocked</em>
 * lives in {@code milestones.yml}. Everything the rest of the plugin asks - is the Nether open, how
 * big is Nordtal's border - is derived from putting those two together, on every portal ignition
 * and every balloon click, which is why it is derived once here and not queried at the point of use.
 */
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

    /**
     * An admin completing a milestone out of order is an escape hatch the design keeps on purpose.
     * It must not shrink the world on the next restart.
     */
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

    /**
     * A key in the database that the file no longer declares contributes nothing rather than
     * throwing. By the time somebody is standing at a balloon it is far too late to complain about
     * the config; {@code TrackValidation} does that at load, when it can still be acted on.
     */
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
