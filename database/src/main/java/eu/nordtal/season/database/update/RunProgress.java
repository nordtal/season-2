package eu.nordtal.season.database.update;

import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/** How far a run is: the finished share of its steps, each weighted by how long its kind takes. */
public final class RunProgress {

    private RunProgress() {}

    /**
     * Returns the median seconds of a step of the kind, as measured on the finished rows of {@code run_step}.
     * A kind that took under a second, or never ran, weighs one.
     */
    static int seconds(final RunStep.Kind kind) {
        return switch (kind) {
            case COUNTDOWN -> 60;
            case STANDBY_UP -> 13;
            case VERIFY -> 6;
            case HAND_OVER -> 5;
            case DUMP_DATABASE, STANDBY_DOWN -> 4;
            case STOP, INSTALL -> 3;
            case MIGRATE, START -> 2;
            default -> 1;
        };
    }

    /**
     * One row of {@code run_step}.
     *
     * @param service the service the step acts on, {@code null} for a step of the whole run
     * @param outcome how it ended, {@code null} while it runs
     */
    public record Step(RunStep.Kind kind, @Nullable String service, RunStep.@Nullable Outcome outcome) {}

    /**
     * Returns the percentage of a running run, at most 99.
     *
     * @param steps the rows written so far
     * @param moving the services the run stops, each of which still has a stop and a health check ahead until written
     * @return the percentage, or empty when nothing is known
     */
    public static OptionalInt percent(final List<Step> steps, final Collection<String> moving) {
        long done = 0;
        long total = 0;
        for (final Step step : steps) {
            if (step.outcome() == RunStep.Outcome.SKIPPED) {
                continue;
            }
            total += seconds(step.kind());
            if (step.outcome() != null) {
                done += seconds(step.kind());
            }
        }
        total += ahead(steps, moving);
        if (total == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.min(99, done * 100 / total));
    }

    /** Returns the weight of the steps every run that stops services has ahead and has not written yet. */
    private static long ahead(final List<Step> steps, final Collection<String> moving) {
        if (moving.isEmpty()) {
            return 0;
        }
        long ahead = 0;
        for (final RunStep.Kind kind : List.of(RunStep.Kind.PLAYERS_MOVED, RunStep.Kind.PLAYERS_BACK)) {
            if (steps.stream().noneMatch(step -> step.kind() == kind)) {
                ahead += seconds(kind);
            }
        }
        for (final String service : moving) {
            for (final RunStep.Kind kind : List.of(RunStep.Kind.STOP, RunStep.Kind.VERIFY)) {
                if (steps.stream().noneMatch(step -> step.kind() == kind && service.equals(step.service()))) {
                    ahead += seconds(kind);
                }
            }
        }
        return ahead;
    }
}
