package eu.nordtal.s2.smp.state;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the track has handed out so far, held in memory so the main thread can ask without touching
 * the database.
 *
 * <p>Two questions are asked constantly - "is the Nether open?" on every portal ignition and every
 * balloon click, and "how big is Nordtal's border?" on every unlock - and both are answered from
 * rows that change a handful of times a season. Reading them from PostgreSQL at the point of use
 * would be a main-thread query per click, which is the mistake this repository already made once.
 *
 * <p>Refreshed from an async task; read from anywhere. The fields are volatile rather than
 * synchronised because a reader that is one refresh behind sees the previous truth, which for
 * "the Nether opened four milliseconds ago" is not a problem worth a lock.
 */
public final class SeasonState {

    private volatile Set<Unlock> unlocked = Collections.unmodifiableSet(EnumSet.noneOf(Unlock.class));
    private volatile int borderDiameter;
    private volatile List<String> completedKeys = List.of();

    /**
     * Recomputes from the completed milestone keys and the track that defines them.
     *
     * <p>The database holds progress and the file holds definition, so this is where the two meet: a
     * completed key the file no longer declares contributes nothing rather than throwing, because
     * by the time a player is standing at a balloon it is far too late to complain about the config
     * - {@code TrackValidation} does that at load, which is when somebody can act on it.
     */
    public void refresh(final List<String> completed, final MilestoneTrack track) {
        final Set<Unlock> found = EnumSet.noneOf(Unlock.class);
        int border = 0;
        for (final String key : completed) {
            final Milestone milestone = track.milestone(key).orElse(null);
            if (milestone == null) {
                continue;
            }
            found.add(milestone.unlock());
            if (milestone.unlock() == Unlock.BORDER) {
                border = Math.max(border, milestone.borderDiameter());
            }
        }
        // A milestone that moves the border is still a border milestone even if a later one does
        // not - take the largest any completed milestone asked for, so an out-of-order completion
        // by an admin cannot shrink the world.
        this.unlocked = Collections.unmodifiableSet(found);
        this.borderDiameter = border;
        this.completedKeys = List.copyOf(completed);
    }

    public Set<Unlock> unlocked() {
        return unlocked;
    }

    public boolean isUnlocked(final Unlock unlock) {
        return unlocked.contains(unlock);
    }

    /** The border every completed milestone adds up to, or 0 when none has moved it yet. */
    public int borderDiameter() {
        return borderDiameter;
    }

    public List<String> completedKeys() {
        return completedKeys;
    }
}
