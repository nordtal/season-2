package eu.nordtal.season.smp.state;

import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.Unlock;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Holds the unlocks, the open border regions and the active milestone in memory.
 * The methods that refresh it run asynchronously, and every reader may run on any thread.
 */
public final class SeasonState {

    private volatile Set<Unlock> unlocked = Collections.unmodifiableSet(EnumSet.noneOf(Unlock.class));
    private volatile Set<String> borderRegions = Set.of();
    private volatile List<String> completedKeys = List.of();
    private volatile Active active = Active.UNREAD;

    /**
     * The active milestone and the progress of its objectives.
     *
     * @param key the key of the active milestone, or null when no milestone is active
     * @param unread whether {@link SeasonState} has not been refreshed yet
     */
    public record Active(@Nullable String key, List<ObjectiveRow> objectives, boolean unread) {

        /** No milestone is active. */
        public static final Active NONE = new Active(null, List.of(), false);

        /** The value before the first {@link SeasonState#refreshActive}. */
        public static final Active UNREAD = new Active(null, List.of(), true);

        public Active {
            objectives = List.copyOf(objectives);
        }

        /** Returns the mean of {@code ObjectiveRow#ratio} over the objectives, or 0 without objectives. */
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
     * Sets the unlocks and the open border regions from the milestones in {@code completed}.
     * It ignores a key that {@code track} does not contain.
     */
    public void refresh(final List<String> completed, final MilestoneTrack track) {
        final Set<Unlock> found = EnumSet.noneOf(Unlock.class);
        final Set<String> regions = new LinkedHashSet<>();
        for (final String key : completed) {
            final Milestone milestone = track.milestone(key).orElse(null);
            if (milestone == null) {
                continue;
            }
            found.add(milestone.unlock());
            if (milestone.unlock() == Unlock.BORDER) {
                regions.addAll(milestone.borderRegions());
            }
        }
        this.unlocked = Collections.unmodifiableSet(found);
        this.borderRegions = Collections.unmodifiableSet(regions);
        this.completedKeys = List.copyOf(completed);
    }

    public Set<Unlock> unlocked() {
        return unlocked;
    }

    public boolean isUnlocked(final Unlock unlock) {
        return unlocked.contains(unlock);
    }

    /** Returns the names of the overworld regions the completed milestones open, in track order. */
    public Set<String> borderRegions() {
        return borderRegions;
    }

    public List<String> completedKeys() {
        return completedKeys;
    }

    public void refreshActive(final @Nullable String key, final List<ObjectiveRow> objectives) {
        this.active = new Active(key, objectives, false);
    }

    public Active active() {
        return active;
    }
}
