package eu.nordtal.s2.smp.milestone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The whole track, in file order, and the only thing that knows what comes after what.
 *
 * The database stores no order; after the last milestone there are none, and new ones are appended to the file.
 */
public final class MilestoneTrack {

    private final List<Milestone> milestones;
    private final Map<String, Integer> indexByKey;

    /**
     * Creates the track.
     *
     * @param milestones the milestones in file order, with unique keys
     * @throws IllegalArgumentException on a duplicate key
     */
    public MilestoneTrack(final List<Milestone> milestones) {
        this.milestones = List.copyOf(Objects.requireNonNull(milestones, "milestones"));

        final Map<String, Integer> index = new LinkedHashMap<>();
        for (int position = 0; position < this.milestones.size(); position++) {
            final String key = this.milestones.get(position).key();
            if (index.put(key, position) != null) {
                throw new IllegalArgumentException("Duplicate milestone key '" + key + "'");
            }
        }
        this.indexByKey = Map.copyOf(index);
    }

    /** Returns every milestone, in file order. */
    public List<Milestone> milestones() {
        return milestones;
    }

    /** Returns how many milestones there are. */
    public int size() {
        return milestones.size();
    }

    /** Returns the milestone with this key, if the file declares it. */
    public Optional<Milestone> milestone(final String key) {
        final Integer position = indexByKey.get(key);
        return position == null ? Optional.empty() : Optional.of(milestones.get(position));
    }

    /** Returns the position of this key in the file, or {@code -1} if the file does not declare it. */
    public int positionOf(final String key) {
        return indexByKey.getOrDefault(key, -1);
    }

    /** Returns the milestone after this one, or empty at the end of the track, which is a real state. */
    public Optional<Milestone> after(final String key) {
        final int position = positionOf(key);
        if (position < 0 || position + 1 >= milestones.size()) {
            return Optional.empty();
        }
        return Optional.of(milestones.get(position + 1));
    }

    /** Returns the milestone the track starts at, or empty for an empty file. */
    public Optional<Milestone> first() {
        return milestones.isEmpty() ? Optional.empty() : Optional.of(milestones.get(0));
    }

    /**
     * Returns the milestone that should be active: the first one in file order that is not completed.
     *
     * @param completed the keys of every completed milestone
     * @return empty once every milestone is completed
     */
    public Optional<Milestone> next(final java.util.Collection<String> completed) {
        return milestones.stream().filter(m -> !completed.contains(m.key())).findFirst();
    }

    /** Returns every milestone key, in file order. */
    public List<String> keys() {
        return milestones.stream().map(Milestone::key).toList();
    }

    /** Returns the sum of every objective pot on the track, the season's whole aura budget. */
    public int totalPot() {
        return milestones.stream().mapToInt(Milestone::totalPot).sum();
    }
}
