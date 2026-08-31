package eu.nordtal.s2.smp.milestone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The whole track, in file order.
 *
 * <p>One linear chain. There is deliberately <b>no ordering column in the database</b> -
 * {@code V6__smp.sql} says so - because the order is the order of the YAML file, and storing it
 * would create a second answer that a file edit could contradict. This class is therefore the only
 * thing that knows what comes after what.
 *
 * <h2>What "the track has run out" means</h2>
 * After the last milestone there simply are none: the season carries on with building, duels, aura
 * and prestige, and the HUD shows the dimension alone. New milestones can be appended at any time,
 * which is exactly why they are not compiled in - and appending one is the planned response to a
 * track that finishes early, because scaling targets to the live player count was rejected.
 */
public final class MilestoneTrack {

    private final List<Milestone> milestones;
    private final Map<String, Integer> indexByKey;

    /**
     * @param milestones the milestones in file order; keys must be unique, which
     *                   {@link TrackShape#validate} checks with a message a person can act on
     * @throws IllegalArgumentException on a duplicate key, because a track with two milestones of
     *                                  one name has no single answer to "what comes next"
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

    /** @return every milestone, in file order */
    public List<Milestone> milestones() {
        return milestones;
    }

    /** @return how many there are */
    public int size() {
        return milestones.size();
    }

    /**
     * @param key a milestone key
     * @return the milestone, if the file declares it
     */
    public Optional<Milestone> milestone(final String key) {
        final Integer position = indexByKey.get(key);
        return position == null ? Optional.empty() : Optional.of(milestones.get(position));
    }

    /**
     * @param key a milestone key
     * @return its position in the file, or {@code -1} if the file does not declare it
     */
    public int positionOf(final String key) {
        return indexByKey.getOrDefault(key, -1);
    }

    /**
     * @param key a milestone key the file declares
     * @return the one after it, or empty at the end of the track - which is a real state and not an
     *         error, because after the last milestone there simply are none
     */
    public Optional<Milestone> after(final String key) {
        final int position = positionOf(key);
        if (position < 0 || position + 1 >= milestones.size()) {
            return Optional.empty();
        }
        return Optional.of(milestones.get(position + 1));
    }

    /** @return the milestone the track starts at, or empty for an empty file */
    public Optional<Milestone> first() {
        return milestones.isEmpty() ? Optional.empty() : Optional.of(milestones.get(0));
    }

    /** @return every milestone key, in file order */
    public List<String> keys() {
        return milestones.stream().map(Milestone::key).toList();
    }

    /** @return the sum of every objective pot on the track, which is the season's whole aura budget */
    public int totalPot() {
        return milestones.stream().mapToInt(Milestone::totalPot).sum();
    }
}
