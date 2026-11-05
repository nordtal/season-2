package eu.nordtal.season.proxy.update;

import eu.nordtal.season.database.update.RunProgress;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateRequest;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Decides from the newest mode switch run whether it holds every player in the waiting room. */
final class ModeSwitch {

    private ModeSwitch() {}

    /**
     * Returns whether the switch holds every player, admins included.
     * A running switch holds from the end of its countdown until its target passes its check. A failed one holds.
     *
     * @param run the newest switch run, {@code null} without one
     * @param target the server that the switch makes active
     */
    static boolean holds(
            final @Nullable UpdateRequest run,
            final List<RunProgress.Step> steps,
            final String target,
            final Instant now) {
        if (run == null) {
            return false;
        }
        return switch (run.status()) {
            case FAILED -> true;
            case RUNNING -> moving(run, now) && !verified(steps, target);
            case PENDING, DONE, CANCELLED -> false;
        };
    }

    /** Returns whether the countdown of the run has ended, so it moves the players. */
    static boolean moving(final UpdateRequest run, final Instant now) {
        final Instant end = run.countdownEnd();
        return end != null && !now.isBefore(end);
    }

    /** Returns whether the check of the target has passed or was skipped, because the target already ran. */
    static boolean verified(final List<RunProgress.Step> steps, final String target) {
        for (final RunProgress.Step step : steps) {
            if (step.kind() == RunStep.Kind.VERIFY
                    && target.equals(step.service())
                    && (step.outcome() == RunStep.Outcome.DONE
                            || step.outcome() == RunStep.Outcome.WARNING
                            || step.outcome() == RunStep.Outcome.SKIPPED)) {
                return true;
            }
        }
        return false;
    }
}
