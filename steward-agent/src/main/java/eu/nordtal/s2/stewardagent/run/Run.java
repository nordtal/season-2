package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The one sequence every kind of run goes through; a kind only plans, see {@link Kinds}.
 *
 * Open the standbys, count down, evacuate, stop, carry out the kind's payload, start, verify, close and settle.
 */
final class Run {

    private final Runner runner;
    private final UpdateRun steps;
    private final RuntimeResult runtime;
    private final Consumer<UpdateReport> progress;

    Run(
            final Runner runner,
            final UpdateRun steps,
            final RuntimeResult runtime,
            final Consumer<UpdateReport> progress) {
        this.runner = runner;
        this.steps = steps;
        this.runtime = runtime;
        this.progress = progress;
    }

    /**
     * What happens between the stop and the start, with every server the run stopped still down.
     *
     * Throwing {@link Abort} starts everything again and fails the run with the report it carries.
     */
    @FunctionalInterface
    interface Payload {

        /** Nothing between the stop and the start. */
        Payload NONE = (steps, stopped) -> new Done(stopped.report(), false);

        /**
         * @param steps this run's own steps, which know which stop could not be verified
         */
        Done carryOut(UpdateRun steps, UpdateRun.Stopped stopped);
    }

    /**
     * What a payload left behind.
     *
     * @param failed whether something in it failed that the report's lines do not show
     */
    record Done(UpdateReport report, boolean failed) {}

    /** Ends a payload early: everything stopped is started again and the run fails with this report. */
    static final class Abort extends RuntimeException {

        private final transient UpdateReport report;

        Abort(final UpdateReport report) {
            super(null, null, false, false);
            this.report = report;
        }
    }

    /**
     * What one kind decided before anything moved.
     *
     * @param planned the report so far, with a {@code PLANNED} line for every service the run stops
     * @param announced whether the run counts down on its row, so the proxy and the feeds warn first
     * @param stops whether the run stops what it plans; only a start, whose servers are already down, does not
     * @param payload what happens while the servers are down
     * @param startsAgain whether what was stopped is started again, which only a take-down leaves out
     * @param alsoStarts services the run starts although it did not stop them, as a release of a hold does
     * @param images the services to recreate on a newer image as they start
     * @param foreign the images nobody here builds to renew once the rest is back, announced like a stop
     * @param refusedStop the first words of the report when a service did not stop, or {@code null} to carry on
     * @param refusedStandby the report's note when a standby did not come up, from the standby's own sentence
     * @param what what the run is called in a sentence, such as "this restart"
     * @param atRisk what an unverified stop put at risk, as the middle of a sentence
     */
    record Plan(
            UpdateReport planned,
            boolean announced,
            boolean stops,
            Payload payload,
            boolean startsAgain,
            List<String> alsoStarts,
            ImageResult images,
            List<String> foreign,
            @Nullable String refusedStop,
            Function<String, String> refusedStandby,
            String what,
            String atRisk,
            Runner.Doubt doubt,
            boolean alreadyFailed) {

        Plan {
            alsoStarts = List.copyOf(alsoStarts);
            foreign = List.copyOf(foreign);
        }

        /** A plan with the defaults most kinds share: announced, started again, nothing renewed. */
        static Plan of(
                final UpdateReport planned,
                final Payload payload,
                final @Nullable String refusedStop,
                final Function<String, String> refusedStandby,
                final String what,
                final String atRisk,
                final Runner.Doubt doubt) {
            return new Plan(
                    planned,
                    true,
                    true,
                    payload,
                    true,
                    List.of(),
                    ImageResult.of(java.util.Map.of()),
                    List.of(),
                    refusedStop,
                    refusedStandby,
                    what,
                    atRisk,
                    doubt,
                    false);
        }

        Plan announced(final boolean announced) {
            return new Plan(
                    planned,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusedStop,
                    refusedStandby,
                    what,
                    atRisk,
                    doubt,
                    alreadyFailed);
        }

        /** A run that stops nothing and warns nobody, since everything it starts is already down. */
        Plan stoppingNothing() {
            return new Plan(
                    planned,
                    false,
                    false,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusedStop,
                    refusedStandby,
                    what,
                    atRisk,
                    doubt,
                    alreadyFailed);
        }

        Plan leavingThemDown() {
            return new Plan(
                    planned,
                    announced,
                    stops,
                    payload,
                    false,
                    alsoStarts,
                    images,
                    foreign,
                    refusedStop,
                    refusedStandby,
                    what,
                    atRisk,
                    doubt,
                    alreadyFailed);
        }

        Plan alsoStarting(final List<String> services) {
            return new Plan(
                    planned,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    services,
                    images,
                    foreign,
                    refusedStop,
                    refusedStandby,
                    what,
                    atRisk,
                    doubt,
                    alreadyFailed);
        }

        Plan renewing(final ImageResult newer, final List<String> renewed, final boolean failed) {
            return new Plan(
                    planned,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    newer,
                    renewed,
                    refusedStop,
                    refusedStandby,
                    what,
                    atRisk,
                    doubt,
                    failed);
        }

        /** What the countdown names as moving: every service stopped, and every foreign image renewed. */
        List<String> moving() {
            final Set<String> moving = new LinkedHashSet<>(Runner.movingServices(planned));
            moving.addAll(foreign);
            return List.copyOf(moving);
        }
    }

    /** Carries the plan out, never throwing, so every path from a stop ends in a start. */
    Outcome carryOut(final UpdateRequest request, final Plan plan) {
        final Choreography choreography = new Choreography(runner.containers, runner.occupancy(), runner.waiting);
        UpdateReport planned = plan.planned();
        final List<String> moving = plan.stops() ? Runner.movingServices(planned) : List.of();
        final Choreography.Window window = choreography.open(moving);
        if (!window.opened()) {
            return Outcome.failed(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.FAILED)
                    .withNote(plan.refusedStandby().apply(window.refusal()))));
        }
        if (!window.isEmpty()) {
            planned = planned.withNote(String.join(", ", window.standbys()) + " started and healthy, so " + plan.what()
                    + " has somewhere to put the players.");
            progress.accept(planned);
        }
        try {
            if (plan.announced() && !runner.countDown(request.id(), planned, plan.moving(), progress)) {
                return Runner.cancelled();
            }

            // After the countdown the run waits for the players to move, then stops regardless after ten seconds.
            final String stillOn = choreography.waitUntilEmpty(moving);
            if (stillOn != null) {
                planned = planned.withNote(stillOn);
                progress.accept(planned);
            }

            final UpdateRun.Stopped stopped =
                    plan.stops() ? steps.stop(planned, runtime) : new UpdateRun.Stopped(planned, List.of(), runtime);
            final Outcome refused = refuseIfNotStopped(plan, planned, stopped);
            if (refused != null) {
                return refused;
            }

            final Done done;
            try {
                done = plan.payload().carryOut(steps, stopped);
            } catch (final Abort abort) {
                // Started again before this is reported, so a failed payload never leaves the network stopped.
                final UpdateReport back = steps.start(new UpdateRun.Stopped(abort.report, stopped.services(), runtime));
                return Outcome.failed(UpdateReports.toJson(
                        steps.verify(back, stopped.services(), runner.waiting).withStage(UpdateReport.Stage.FAILED)));
            }

            return finish(plan, stopped, done, choreography);
        } finally {
            // Every exit closes the standbys, so none is left running all night.
            choreography.close();
        }
    }

    /** Starts what the run stopped and what the plan adds, verifies, renews the foreign images and settles. */
    private Outcome finish(
            final Plan plan, final UpdateRun.Stopped stopped, final Done done, final Choreography choreography) {
        UpdateReport report = done.report();
        if (plan.startsAgain()) {
            final List<String> starting = new ArrayList<>(stopped.services());
            plan.alsoStarts().stream()
                    .filter(service -> !starting.contains(service))
                    .forEach(starting::add);
            final UpdateReport started =
                    steps.start(new UpdateRun.Stopped(report, List.copyOf(starting), runtime), plan.images());
            report = steps.verify(started, starting, runner.waiting);
        }

        // Last, once the Minecraft services are healthy again, and postgres last of all.
        final UpdateReport renewed =
                ForeignImages.renewForeign(runner.containers, steps, report, plan.foreign(), progress, runner.waiting);

        final UpdateReport finished = Runner.settle(
                Runner.noteStandbys(renewed, choreography.close()),
                steps.unverifiedStops(),
                plan.atRisk(),
                plan.alreadyFailed() || done.failed(),
                plan.doubt());
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }

    /** Starts back and fails the run when a service the plan stops did not stop and the kind refuses to go on. */
    private @Nullable Outcome refuseIfNotStopped(
            final Plan plan, final UpdateReport planned, final UpdateRun.Stopped stopped) {
        if (plan.refusedStop() == null) {
            return null;
        }
        final List<String> notStopped = Runner.servicesThatRefused(planned, stopped.services());
        if (notStopped.isEmpty()) {
            return null;
        }
        final UpdateReport back = steps.start(new UpdateRun.Stopped(
                stopped.report()
                        .withNote(plan.refusedStop() + " " + String.join(", ", notStopped) + " could not be stopped, "
                                + "and every service that did stop has been started again."),
                stopped.services(),
                runtime));
        return Outcome.failed(UpdateReports.toJson(
                steps.verify(back, stopped.services(), runner.waiting).withStage(UpdateReport.Stage.FAILED)));
    }
}
