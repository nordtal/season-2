package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The sequence every kind of run goes through. A kind only plans, see {@link Kinds}.
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
     * The work between the stop and the start.
     *
     * Throwing {@link Abort} starts everything again and fails the run with the report it carries.
     */
    @FunctionalInterface
    interface Payload {

        Payload NONE = (steps, stopped) -> new Done(stopped.report(), false);

        Done carryOut(UpdateRun steps, UpdateRun.Stopped stopped);
    }

    /**
     * What a payload left behind.
     *
     * @param failed whether something failed that the lines of the report do not show
     * @param afterwards what the run does once everything it stopped is back
     * @param leftDown services the run stopped and does not start again, because their data is incomplete
     */
    record Done(
            UpdateReport report,
            boolean failed,
            java.util.function.UnaryOperator<UpdateReport> afterwards,
            List<String> leftDown) {

        Done {
            leftDown = List.copyOf(leftDown);
        }

        Done(final UpdateReport report, final boolean failed) {
            this(report, failed, java.util.function.UnaryOperator.identity());
        }

        Done(
                final UpdateReport report,
                final boolean failed,
                final java.util.function.UnaryOperator<UpdateReport> afterwards) {
            this(report, failed, afterwards, List.of());
        }
    }

    /** Ends a payload early: everything stopped is started again and the run fails with this report. */
    static final class Abort extends RuntimeException {

        private final transient UpdateReport report;

        Abort(final UpdateReport report) {
            super(null, null, false, false);
            this.report = report;
        }
    }

    /**
     * The plan of a kind.
     *
     * @param planned the report so far, with a {@code PLANNED} line for every service the run stops
     * @param announced whether the run counts down, so the proxy and the feeds warn first
     * @param stops whether the run stops what it plans, which a start does not
     * @param payload the work while the servers are down
     * @param startsAgain whether the run starts what it stopped, which only a take-down does not
     * @param alsoStarts services the run starts although it did not stop them, as the release of a hold does
     * @param images the services to recreate as they start, because their container is out of date
     * @param foreign the images nobody here builds, recreated once the rest is back and announced like a stop
     * @param undertaking what the run does while the servers are down, which its notes name
     * @param refusesUnstopped whether a service that did not stop ends the run before its payload
     * @param remade services whose container is created again as they start
     * @param pulls whether recreating a container pulls its image first
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
            UpdateReport.Undertaking undertaking,
            boolean refusesUnstopped,
            Runner.Doubt doubt,
            boolean alreadyFailed,
            List<String> remade,
            boolean pulls) {

        Plan {
            alsoStarts = List.copyOf(alsoStarts);
            foreign = List.copyOf(foreign);
            remade = List.copyOf(remade);
        }

        static Plan of(
                final UpdateReport planned,
                final Payload payload,
                final UpdateReport.Undertaking undertaking,
                final boolean refusesUnstopped,
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
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    false,
                    List.of(),
                    true);
        }

        Plan announced(final boolean warned) {
            return new Plan(
                    planned,
                    warned,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
        }

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
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
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
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
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
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
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
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    failed,
                    remade,
                    pulls);
        }

        /**
         * Makes the containers of these services again: ours as they start, the foreign ones once the rest is back.
         *
         * @param pulling whether each image is pulled first
         */
        Plan remaking(final List<String> ours, final List<String> theirs, final boolean pulling) {
            return new Plan(
                    planned,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    theirs,
                    undertaking,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    ours,
                    pulling);
        }

        /**
         * Returns what the countdown names: every stopped service and every recreated foreign image.
         */
        List<String> moving() {
            final Set<String> moving = new LinkedHashSet<>(Runner.movingServices(planned));
            moving.addAll(foreign);
            return List.copyOf(moving);
        }
    }

    /** Carries the plan out. It never throws, so every stop ends in a start. */
    Outcome carryOut(final UpdateRequest request, final Plan plan) {
        final Choreography choreography =
                new Choreography(runner.containers, runner.occupancy(), runner.waiting, steps.record);
        UpdateReport planned = plan.planned();
        final List<String> moving = plan.stops() ? Runner.movingServices(planned) : List.of();
        final Choreography.Window window = choreography.open(moving);
        if (!window.opened()) {
            return Outcome.failed(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.FAILED)
                    .withNote(Note.failed(Step.STANDBY, TEXTS.report().noStandby()))
                    .withNotes(window.refusal())));
        }
        if (!window.isEmpty()) {
            planned = planned.withNotes(
                    Note.done(Step.STANDBY, TEXTS.report().standbysReady()).each(window.standbys()));
            progress.accept(planned);
        }
        try {
            final Steps.Step players = plan.announced()
                    ? runner.countDown(request.id(), planned, plan.moving(), progress, steps.record)
                    : null;
            if (plan.announced() && players == null) {
                return Runner.cancelled();
            }

            // The players get ten seconds after the countdown, then the run stops the services.
            final Choreography.Left left = choreography.waitUntilEmpty(moving);
            if (players != null) {
                players.end(left.outcome(), left.reason());
            } else if (left.reason() != null) {
                steps.record.ended(RunStep.Kind.PLAYERS_MOVED, null, left.outcome(), left.reason());
            }
            if (!left.notes().isEmpty()) {
                planned = planned.withNotes(left.notes());
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

            return finish(plan, stopped, done, choreography, players != null);
        } finally {
            choreography.close();
        }
    }

    private Outcome finish(
            final Plan plan,
            final UpdateRun.Stopped stopped,
            final Done done,
            final Choreography choreography,
            final boolean moved) {
        UpdateReport report = done.report();
        if (plan.startsAgain()) {
            final List<String> starting = new ArrayList<>(stopped.services());
            plan.alsoStarts().stream()
                    .filter(service -> !starting.contains(service))
                    .forEach(starting::add);
            starting.removeAll(done.leftDown());
            final UpdateReport started = steps.start(
                    new UpdateRun.Stopped(report, List.copyOf(starting), runtime),
                    plan.images(),
                    plan.remade(),
                    plan.pulls());
            report = steps.verify(started, starting, runner.waiting);
        }

        // The proxy counts the players back. The standbys give theirs back as they close.
        final Steps.Step back = moved ? steps.record.begin(RunStep.Kind.PLAYERS_BACK) : null;
        // The standbys close first, because caddy publishes their ports.
        final List<Note> closed = choreography.close();
        if (back != null) {
            back.done();
        }
        // This runs once the Minecraft services are healthy, with postgres last.
        final UpdateReport renewedForeign = ForeignImages.renewForeign(
                runner.containers, steps, report, plan.foreign(), plan.pulls(), progress, runner.waiting);
        final UpdateReport foreign = done.afterwards().apply(renewedForeign);
        // Last of all, since the agent it creates leaves the row alone.
        final UpdateReport renewed = runner.oneShot ? renewAgent(foreign) : foreign;

        final UpdateReport finished = Runner.settle(
                renewed.withNotes(closed),
                steps.unverifiedStops(),
                plan.alreadyFailed() || done.failed(),
                plan.doubt());
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }

    /**
     * Creates the long-running steward-agent at the release of this one-shot and waits for its health.
     */
    private UpdateReport renewAgent(final UpdateReport before) {
        UpdateReport report = before.with(new UpdateReport.ServiceLine(
                AgentWire.SERVICE,
                UpdateReport.State.STARTING,
                List.of(UpdateReport.Change.told("image", TEXTS.report().imageOutdated())),
                UpdateReport.Facts.of(UpdateReport.Act.RENEW)));
        progress.accept(report);
        final Steps.Step install = steps.record
                .begin(RunStep.Kind.INSTALL, AgentWire.SERVICE)
                .change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED);
        final RedeployResult result = runner.containers.renewAgent();
        UpdateRun.endBy(install, result);
        if (!result.triggered()) {
            report = report.with(report.line(AgentWire.SERVICE)
                    .failed(UpdateReport.Facts.refused(UpdateReport.Act.RENEW, result.message())));
            progress.accept(report);
            return report;
        }
        return steps.verify(report, List.of(AgentWire.SERVICE), runner.waiting);
    }

    private @Nullable Outcome refuseIfNotStopped(
            final Plan plan, final UpdateReport planned, final UpdateRun.Stopped stopped) {
        if (!plan.refusesUnstopped()) {
            return null;
        }
        final List<String> notStopped = Runner.servicesThatRefused(planned, stopped.services());
        if (notStopped.isEmpty()) {
            return null;
        }
        final UpdateReport back = steps.start(new UpdateRun.Stopped(
                stopped.report()
                        .withNotes(Note.failed(Step.STOP, TEXTS.report().notStopped(plan.undertaking()))
                                .each(notStopped)),
                stopped.services(),
                runtime));
        return Outcome.failed(UpdateReports.toJson(
                steps.verify(back, stopped.services(), runner.waiting).withStage(UpdateReport.Stage.FAILED)));
    }
}
