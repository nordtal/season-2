package eu.nordtal.s2.smp.config;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.TrackShape;
import eu.nordtal.s2.smp.milestone.TrackValidation;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Turns a loaded {@link MilestonesSpec} into a {@link MilestoneTrack}.
 *
 * The seam between the config system and the engine, and it exists so the engine never sees a jcore proxy:
 * {@code milestone} is a package of plain records with no annotations on them, which is what makes the validation
 * and the payout testable without writing a YAML file first.
 *
 * Two kinds of failure meet here and are deliberately kept apart. An unreadable enum - a {@code type} of
 * {@code HANDIN}, an {@code unlocks} of {@code BOARDER} - is a <b>parse</b> problem and is reported with the value
 * that was written, because "HANDIN is not one of HAND_IN, STATISTIC, ADVANCEMENT" is a sentence somebody can act
 * on. Everything else - a missing item list, two participation gates, a duplicate key - is {@link TrackShape} 's.
 */
public final class Milestones {

    private Milestones() {}

    /**
     * The outcome of reading the file.
     *
     * @param track    the track, or {@code null} when it could not be parsed at all
     * @param problems everything wrong with it; empty means {@code track} is usable
     */
    public record Result(@Nullable MilestoneTrack track, List<TrackValidation.Problem> problems) {

        public Result {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        }

        /** @return whether the file is a usable track */
        public boolean ok() {
            return track != null && problems.isEmpty();
        }

        /** @return the problems as one message, one per line, for a log or a command reply */
        public String describe() {
            return String.join("\n", problems.stream().map(Object::toString).toList());
        }
    }

    /**
     * @param spec the loaded {@code milestones.yml}
     * @return the track and everything wrong with it
     */
    public static Result read(final MilestonesSpec spec) {
        Objects.requireNonNull(spec, "spec");

        final List<TrackValidation.Problem> problems = new ArrayList<>();
        final List<Milestone> milestones = new ArrayList<>();

        final List<MilestonesSpec.MilestoneEntry> entries = spec.milestones() == null ? List.of() : spec.milestones();
        for (final MilestonesSpec.MilestoneEntry entry : entries) {
            readMilestone(entry, problems, milestones);
        }

        problems.addAll(TrackShape.validate(milestones));
        return new Result(buildTrack(milestones, problems), problems);
    }

    private static void readMilestone(
            final MilestonesSpec.MilestoneEntry entry,
            final List<TrackValidation.Problem> problems,
            final List<Milestone> milestones) {
        final String key = entry.key() == null ? "" : entry.key().trim();

        final Unlock unlock = Unlock.parse(entry.unlocks()).orElse(null);
        if (unlock == null) {
            problems.add(new TrackValidation.Problem(
                    key,
                    null,
                    "unlocks '" + entry.unlocks() + "', which is not one of BORDER, NETHER, END, " + "NOTHING."));
            return;
        }

        final List<Objective> objectives = new ArrayList<>();
        final List<MilestonesSpec.ObjectiveEntry> objectiveEntries =
                entry.objectives() == null ? List.of() : entry.objectives();
        for (final MilestonesSpec.ObjectiveEntry objectiveEntry : objectiveEntries) {
            readObjective(key, objectiveEntry, problems, objectives);
        }

        milestones.add(new Milestone(
                key, unlock, entry.borderDiameter(), entry.objectivePot(), entry.adminUnlocked(), objectives));
    }

    private static void readObjective(
            final String milestoneKey,
            final MilestonesSpec.ObjectiveEntry objectiveEntry,
            final List<TrackValidation.Problem> problems,
            final List<Objective> objectives) {
        final String objectiveKey =
                objectiveEntry.key() == null ? "" : objectiveEntry.key().trim();
        final ObjectiveType type = ObjectiveType.parse(objectiveEntry.type()).orElse(null);
        if (type == null) {
            problems.add(new TrackValidation.Problem(
                    milestoneKey,
                    objectiveKey,
                    "has type '" + objectiveEntry.type() + "', which is not one of HAND_IN, "
                            + "STATISTIC, ADVANCEMENT."));
            return;
        }
        objectives.add(new Objective(
                objectiveKey,
                type,
                trimmed(objectiveEntry.role()),
                objectiveEntry.target(),
                trimmedList(objectiveEntry.items()),
                trimmed(objectiveEntry.statistic()),
                trimmedList(objectiveEntry.subjects()),
                trimmed(objectiveEntry.advancement())));
    }

    private static @Nullable MilestoneTrack buildTrack(
            final List<Milestone> milestones, final List<TrackValidation.Problem> problems) {
        try {
            return new MilestoneTrack(milestones);
        } catch (final IllegalArgumentException duplicateKey) {
            // TrackShape already reported the duplicate; this only keeps the exception from escaping read().
            problems.add(new TrackValidation.Problem(
                    null,
                    null,
                    Objects.requireNonNullElse(duplicateKey.getMessage(), "a milestone key is declared twice.")));
            return null;
        }
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> trimmedList(final List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
