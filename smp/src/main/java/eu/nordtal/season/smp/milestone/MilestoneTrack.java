package eu.nordtal.season.smp.milestone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The milestones in file order. The file gives the only order, the database stores none. */
public final class MilestoneTrack {

    private final List<Milestone> milestones;
    private final Map<String, Integer> indexByKey;

    /**
     * Creates the track.
     *
     * @throws IllegalArgumentException when two milestones have the same key
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

    public List<Milestone> milestones() {
        return milestones;
    }

    public int size() {
        return milestones.size();
    }

    public Optional<Milestone> milestone(final String key) {
        final Integer position = indexByKey.get(key);
        return position == null ? Optional.empty() : Optional.of(milestones.get(position));
    }

    /** Returns the position of {@code key} in the file, or {@code -1} when the file has no such key. */
    public int positionOf(final String key) {
        return indexByKey.getOrDefault(key, -1);
    }

    /** Returns the milestone after {@code key}, or empty when {@code key} is unknown or last. */
    public Optional<Milestone> after(final String key) {
        final int position = positionOf(key);
        if (position < 0 || position + 1 >= milestones.size()) {
            return Optional.empty();
        }
        return Optional.of(milestones.get(position + 1));
    }

    public Optional<Milestone> first() {
        return milestones.isEmpty() ? Optional.empty() : Optional.of(milestones.get(0));
    }

    /**
     * Returns the first milestone in file order whose key is not in {@code completed}.
     *
     * @return empty when every milestone is in {@code completed}
     */
    public Optional<Milestone> next(final java.util.Collection<String> completed) {
        return milestones.stream().filter(m -> !completed.contains(m.key())).findFirst();
    }

    public List<String> keys() {
        return milestones.stream().map(Milestone::key).toList();
    }
}
