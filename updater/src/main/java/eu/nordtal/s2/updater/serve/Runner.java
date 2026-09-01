package eu.nordtal.s2.updater.serve;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.arcane.Arcane;
import eu.nordtal.s2.updater.arcane.RedeployResult;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.plan.Report;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.run.Runs;
import eu.nordtal.s2.updater.schema.RunLock;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Carries out one claimed {@link UpdateRequest} and says what happened.
 *
 * <h2>It never throws</h2>
 * Every path here ends in an {@link Outcome}, including the ones that go wrong. The caller has a
 * row marked {@code RUNNING} that somebody in Discord or in game is watching, and an exception
 * escaping this class would leave that row open forever. So the exception is caught, turned into
 * the text of the answer, and logged with its stack trace where a stack trace belongs.
 *
 * <h2>The three kinds are three different amounts of damage</h2>
 * {@code REPORT} writes nothing at all. {@code APPLY} takes the advisory lock, migrates and moves
 * jars. {@code RESTART} calls Arcane and expects to be killed by its own request.
 */
@Slf4j
public final class Runner implements RequestRunner {

    private final UpdaterSpec config;
    private final Database database;
    private final Arcane arcane;

    public Runner(final @NotNull UpdaterSpec config, final @NotNull Database database,
                  final @NotNull Arcane arcane) {
        this.config = config;
        this.database = database;
        this.arcane = arcane;
    }

    @Override
    public @NotNull Outcome run(final @NotNull UpdateRequest request) {
        try {
            return switch (request.kind()) {
                case REPORT -> report();
                case APPLY -> apply();
                case RESTART -> restart();
            };
        } catch (final RuntimeException failure) {
            log.error("Request {} ({}) failed", request.id(), request.kind(), failure);
            return Outcome.failed("This request failed: " + failure
                    + "\nThe updater's log has the stack trace.");
        }
    }

    // ---------------------------------------------------------------- report

    private Outcome report() {
        final UpdatePlan plan = Runs.resolve(config);
        final String text = Report.render(plan);
        // A plan full of rows that could not be checked is still a report, and the report says so.
        // Marking it FAILED would make "GitHub was briefly unreachable" look like a broken updater.
        return Outcome.done(text);
    }

    // ---------------------------------------------------------------- apply

    private Outcome apply() {
        final Optional<RunLock> lock;
        try {
            lock = RunLock.tryAcquire(database.dataSource());
        } catch (final SQLException failure) {
            return Outcome.failed("Could not reach the database to take the updater lock: " + failure);
        }
        if (lock.isEmpty()) {
            // Refused rather than queued: the other run is moving the same jars, and a plan
            // resolved now would be stale by the time it got its turn anyway.
            return Outcome.failed("Another updater run is in progress - nothing was done."
                    + " That is either the daemon or a `docker compose run --rm updater apply`"
                    + " somebody started on the host. Wait for it to finish and ask again.");
        }

        try (RunLock held = lock.get()) {
            final UpdatePlan plan = Runs.resolve(config);
            final String planText = Report.render(plan);

            // Before a single jar moves, and this order is the design: a plugin must never come up
            // against a schema older than it is, and a migration that fails has to stop the run
            // while nothing has been written.
            try {
                eu.nordtal.s2.updater.schema.Schema.migrate(database);
            } catch (final RuntimeException failure) {
                log.error("The migration failed; no jar was touched", failure);
                return Outcome.failed(planText + "\n\nTHE MIGRATION FAILED AND NOTHING WAS INSTALLED.\n"
                        + failure);
            }

            final ApplyResult result = Runs.apply(config, plan);
            final String text = planText + "\n\n" + Report.render(result);
            return result.hasFailures() ? Outcome.failed(text) : Outcome.done(text);
        }
    }

    // ---------------------------------------------------------------- restart

    /**
     * One Arcane redeploy of the whole project.
     *
     * <p>The successful outcome of this method is usually that it never returns: the redeploy takes
     * this container down while the row is still {@code RUNNING}, and the next start of the updater
     * reads a {@code RESTART} in that state as "it happened". When it does return, something
     * answered - a refusal, a wrong path, an unconfigured Arcane - and that is what goes in the
     * row.</p>
     */
    private Outcome restart() {
        final RedeployResult result = arcane.redeploy();
        if (!result.triggered()) {
            return Outcome.failed(result.message());
        }
        return Outcome.done(result.message());
    }
}
