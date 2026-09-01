package eu.nordtal.s2.smp.state;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.db.ObjectiveRow;

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
    private volatile String activeKey;
    private volatile List<ObjectiveRow> activeObjectives = List.of();

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

    /**
     * Records which milestone is being worked on and how far each of its objectives has got.
     *
     * <p>Refreshed on a timer from an async task, read by HUD line 1 and by the objective board -
     * both of which redraw far more often than the numbers change.
     */
    public void refreshActive(final String key, final List<ObjectiveRow> objectives) {
        this.activeKey = key;
        this.activeObjectives = List.copyOf(objectives);
    }

    /** The milestone being worked on, or empty once the track has run out. */
    public java.util.Optional<String> activeKey() {
        return java.util.Optional.ofNullable(activeKey);
    }

    public List<ObjectiveRow> activeObjectives() {
        return activeObjectives;
    }

    /**
     * How far the active milestone is, as the mean of its objectives.
     *
     * <p>The mean and not the total: objectives have wildly different targets - "3000 stone" beside
     * "8 players earn an advancement" - and summing the raw amounts would make the large one the
     * only one the bar ever moves for. Each objective is worth the same fraction of the milestone,
     * which is also how the pot is split.
     */
    public double activeProgress() {
        final List<ObjectiveRow> objectives = activeObjectives;
        if (objectives.isEmpty()) {
            return 0.0;
        }
        return objectives.stream().mapToDouble(ObjectiveRow::ratio).average().orElse(0.0);
    }
}
