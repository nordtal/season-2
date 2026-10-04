package eu.nordtal.s2.smp.state;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.ObjectiveRow;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the track has handed out so far, held in memory so the main thread can ask without touching the database.
 *
 * Refreshed asynchronously and read from anywhere: volatile fields suffice, as one refresh behind is harmless.
 */
public final class SeasonState {

    private volatile Set<Unlock> unlocked = Collections.unmodifiableSet(EnumSet.noneOf(Unlock.class));
    private volatile int borderDiameter;
    private volatile List<String> completedKeys = List.of();
    private volatile Active active = Active.UNREAD;

    /**
     * The active milestone and its objectives' progress, as one value so a reader never mixes two milestones.
     *
     * @param key the active milestone's key, or null once the track has run out
     * @param objectives its objectives with their progress, never null
     * @param unread whether nothing has been read from the database yet
     */
    public record Active(@Nullable String key, List<ObjectiveRow> objectives, boolean unread) {

        /** No milestone, because the last one is done. */
        public static final Active NONE = new Active(null, List.of(), false);

        /**
         * Not read yet: what the state holds between enable and the first refresh.
         *
         * Distinct from {@link #NONE}, so a surface read after a restart never claims the track is finished.
         */
        public static final Active UNREAD = new Active(null, List.of(), true);

        public Active {
            objectives = List.copyOf(objectives);
        }

        /**
         * How far this milestone is, as the mean of its objectives.
         *
         * The mean, not the total: targets differ wildly, and each objective weighs the same, as in the pot split.
         */
        public double progress() {
            return objectives.isEmpty()
                    ? 0.0
                    : objectives.stream()
                            .mapToDouble(ObjectiveRow::ratio)
                            .average()
                            .orElse(0.0);
        }
    }

    /**
     * Recomputes from the completed milestone keys and the track that defines them.
     *
     * A completed key the track no longer declares contributes nothing; {@code TrackValidation} reports it at load.
     */
    public void refresh(final List<String> completed, final MilestoneTrack track) {
        final Set<Unlock> found = EnumSet.noneOf(Unlock.class);
        // The first milestone's border holds from the start of the track, before anything is unlocked.
        int border = track.first()
                .filter(first -> first.unlock() == Unlock.BORDER)
                .map(Milestone::borderDiameter)
                .orElse(0);
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
        // Takes the largest border any completed milestone asked for.
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

    /** Records which milestone is being worked on and how far each of its objectives has got. */
    public void refreshActive(final @Nullable String key, final List<ObjectiveRow> objectives) {
        this.active = new Active(key, objectives, false);
    }

    /** The active milestone and its progress, in one read so the name and the bar always agree. */
    public Active active() {
        return active;
    }
}
