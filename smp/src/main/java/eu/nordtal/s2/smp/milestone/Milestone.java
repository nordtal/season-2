package eu.nordtal.s2.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One milestone of the track, as the milestone file defines it.
 *
 * <p>All of its objectives must complete before it unlocks and the next one begins - there is no
 * timer anywhere in the track, and the "expected" column in docs/smp.md#the-track is an estimate
 * for planning, not a rule in the code.
 *
 * @param key            the YAML key and {@code smp_milestone.key}: {@code waiting},
 *                       {@code departure}, {@code foothold}, ... Renaming one orphans its stored
 *                       progress and is refused by {@link TrackValidation}
 * @param unlock         what finishing it hands the community
 * @param borderDiameter the Nordtal border this milestone sets, as a <b>diameter</b>, because that
 *                       is what Minecraft's world border takes. Ignored unless {@code unlock} is
 *                       {@link Unlock#BORDER}
 * @param objectivePot   the aura pot of <b>each</b> of this milestone's objectives, not of the
 *                       milestone as a whole. One number rather than one per objective because it
 *                       is derived rather than chosen:
 *                       {@code pot = round((budget ÷ objectives) × 5, to 10)}, and a per-objective
 *                       pot would let that derivation drift silently
 * @param adminUnlocked  whether this milestone is opened by an admin rather than by objectives.
 *                       True for {@code departure} alone, which is the opening expansion at the
 *                       start of the season
 * @param objectives     every objective, in file order; empty for the two opening milestones
 */
public record Milestone(String key, Unlock unlock, int borderDiameter, int objectivePot,
                        boolean adminUnlocked, List<Objective> objectives) {

    public Milestone {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(unlock, "unlock");
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    /**
     * @param key an objective key
     * @return that objective, if this milestone declares it
     */
    public Optional<Objective> objective(final String key) {
        return objectives.stream().filter(objective -> objective.key().equals(key)).findFirst();
    }

    /**
     * @return whether this milestone has nothing to finish. True for {@code waiting} and
     *         {@code departure}, and the engine treats it as "unlocks the moment it becomes active,
     *         unless an admin has to open it"
     */
    public boolean hasNoObjectives() {
        return objectives.isEmpty();
    }

    /** @return the milestone's whole aura budget, which is the pot times the number of objectives */
    public int totalPot() {
        return objectivePot * objectives.size();
    }
}
