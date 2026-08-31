package eu.nordtal.s2.smp.milestone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a milestone file is internally coherent, before it is compared to anything in the
 * database.
 *
 * <p>{@link TrackValidation} answers "may this file replace the running one"; this answers the
 * earlier question, "is this file a track at all". They are separate because they fail for
 * different people: a shape problem is a typo in a diff somebody just wrote, and an orphaning
 * problem is a conflict with a season that has been running for a week.
 *
 * <h2>What it deliberately does not check</h2>
 * <b>That an item name, a statistic or an advancement exists.</b> Resolving any of those needs an
 * initialised Bukkit registry, which is a running server - so a validator that did it could not run
 * at config-load time and could not be tested. The plugin binds the names once at enable and
 * refuses to start on one it cannot resolve, which fails just as fast and says which name it was.
 *
 * <p>It also does not check the arithmetic behind a pot. {@code pot = round((budget ÷ objectives)
 * × 5, to 10)} is how the defaults were derived and docs/smp.md is explicit that the resulting
 * numbers are defaults rather than decisions; a validator enforcing the formula would make retuning
 * a pot impossible, which is the opposite of the point.
 */
public final class TrackShape {

    private TrackShape() {
    }

    /**
     * @param milestones the milestones as parsed from the file, in file order
     * @return every problem found. Empty means the file is a coherent track
     */
    public static List<TrackValidation.Problem> validate(final List<Milestone> milestones) {
        Objects.requireNonNull(milestones, "milestones");
        final List<TrackValidation.Problem> problems = new ArrayList<>();

        if (milestones.isEmpty()) {
            problems.add(new TrackValidation.Problem(null, null,
                    "the track is empty. A season with no milestones has no border to set and "
                            + "nothing for the objective board to show."));
            return problems;
        }

        final Set<String> milestoneKeys = new HashSet<>();
        for (final Milestone milestone : milestones) {
            if (milestone.key().isBlank()) {
                problems.add(new TrackValidation.Problem(null, null, "a milestone has a blank key."));
                continue;
            }
            if (!milestoneKeys.add(milestone.key())) {
                problems.add(new TrackValidation.Problem(milestone.key(), null,
                        "is declared twice. A milestone key is also its primary key in "
                                + "smp_milestone, so two of them have no single answer to "
                                + "'what comes next'."));
            }
            if (milestone.unlock() == Unlock.BORDER && milestone.borderDiameter() <= 0) {
                problems.add(new TrackValidation.Problem(milestone.key(), null,
                        "unlocks a border but its border-diameter is " + milestone.borderDiameter()
                                + ". Border sizes are DIAMETERS, because that is what Minecraft's "
                                + "world border takes."));
            }
            if (milestone.objectivePot() < 0) {
                problems.add(new TrackValidation.Problem(milestone.key(), null,
                        "has a negative objective-pot."));
            }

            problems.addAll(objectiveProblems(milestone));
        }

        return List.copyOf(problems);
    }

    private static List<TrackValidation.Problem> objectiveProblems(final Milestone milestone) {
        final List<TrackValidation.Problem> problems = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        int participationGates = 0;

        for (final Objective objective : milestone.objectives()) {
            final String key = objective.key();
            if (key.isBlank()) {
                problems.add(new TrackValidation.Problem(milestone.key(), null,
                        "an objective has a blank key."));
                continue;
            }
            if (!keys.add(key)) {
                problems.add(new TrackValidation.Problem(milestone.key(), key,
                        "is declared twice; smp_objective is UNIQUE on (milestone_key, key)."));
            }
            if (objective.target() <= 0) {
                problems.add(new TrackValidation.Problem(milestone.key(), key,
                        "has a target of " + objective.target()
                                + "; smp_objective's own CHECK requires it to be positive."));
            }
            if (objective.role().isBlank()) {
                // Never read by the engine, and required anyway: docs/smp.md keeps the roles because
                // they are "what stops a correction from accidentally producing four mining
                // objectives", which only works if every objective has one to read in the diff.
                problems.add(new TrackValidation.Problem(milestone.key(), key,
                        "has no role. It is documentation for whoever edits this file next, not a "
                                + "value the engine reads - which is exactly why it has to be there."));
            }

            problems.addAll(typeProblems(milestone, objective));

            if (objective.isParticipationGate()) {
                participationGates++;
            }
        }

        if (!milestone.objectives().isEmpty() && participationGates != 1) {
            // docs/smp.md#the-rules-the-content-has-to-obey: "every milestone carries exactly one
            // participation gate, and it is always an ADVANCEMENT objective - the only type three
            // industrious people cannot finish alone". A file edit that drops it is the single
            // easiest way to make the whole track soloable, and nothing else would notice.
            problems.add(new TrackValidation.Problem(milestone.key(), null,
                    "has " + participationGates + " ADVANCEMENT objectives; every milestone with "
                            + "objectives carries exactly one, as its participation gate. It is the "
                            + "only type that counts distinct players and therefore the only one "
                            + "three industrious people cannot finish alone."));
        }

        return problems;
    }

    private static List<TrackValidation.Problem> typeProblems(final Milestone milestone,
                                                              final Objective objective) {
        final List<TrackValidation.Problem> problems = new ArrayList<>();
        final String key = objective.key();

        switch (objective.type()) {
            case HAND_IN -> {
                if (objective.items().isEmpty()) {
                    problems.add(new TrackValidation.Problem(milestone.key(), key,
                            "is a HAND_IN with no items. Nothing can be handed in for it, so the "
                                    + "milestone it belongs to could never unlock."));
                }
                forbid(problems, milestone, objective, !objective.statistic().isBlank(), "statistic");
                forbid(problems, milestone, objective, !objective.subjects().isEmpty(), "subjects");
                forbid(problems, milestone, objective, !objective.advancement().isBlank(), "advancement");
            }
            case STATISTIC -> {
                if (objective.statistic().isBlank()) {
                    problems.add(new TrackValidation.Problem(milestone.key(), key,
                            "is a STATISTIC with no statistic named."));
                }
                forbid(problems, milestone, objective, !objective.items().isEmpty(), "items");
                forbid(problems, milestone, objective, !objective.advancement().isBlank(), "advancement");
                // `subjects` may legitimately be empty: a statistic with no substatistic, such as a
                // count of trades, is counted whole.
            }
            case ADVANCEMENT -> {
                if (objective.advancement().isBlank()) {
                    problems.add(new TrackValidation.Problem(milestone.key(), key,
                            "is an ADVANCEMENT with no advancement named."));
                }
                forbid(problems, milestone, objective, !objective.items().isEmpty(), "items");
                forbid(problems, milestone, objective, !objective.statistic().isBlank(), "statistic");
                forbid(problems, milestone, objective, !objective.subjects().isEmpty(), "subjects");
            }
        }
        return problems;
    }

    private static void forbid(final List<TrackValidation.Problem> problems, final Milestone milestone,
                               final Objective objective, final boolean present, final String field) {
        if (present) {
            // A field that belongs to another type is almost always a half-finished edit: somebody
            // changed HAND_IN to STATISTIC and left the item list behind. Saying so is cheaper than
            // ignoring it and having the objective silently count nothing.
            problems.add(new TrackValidation.Problem(milestone.key(), objective.key(),
                    "is a " + objective.type() + " but carries '" + field + "', which belongs to "
                            + "another type. Leaving it behind is what a half-finished type change "
                            + "looks like."));
        }
    }
}
