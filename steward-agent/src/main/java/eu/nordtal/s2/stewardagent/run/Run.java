package eu.nordtal.s2.stewardagent.run;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.messages.MessageRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
     * @param images the services to recreate as they start, since their container is out of date
     * @param foreign the images nobody here builds to make again once the rest is back, announced like a stop
     * @param undertaking what the run does while the servers are down, which its notes name
     * @param refusesUnstopped whether a service that did not stop ends the run before its payload
     * @param remade services whose container is made again as they start, whatever their image's state
     * @param pulls whether making a container again pulls its image first; only a recreate does not
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

        /** A plan with the defaults most kinds share: announced, started again, nothing renewed. */
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
         * @param pulling whether each image is pulled first, as a deploy does, or taken from this host
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
                    .withNote(TEXTS.report().noStandby(plan.undertaking()))
                    .withNote(Objects.requireNonNull(window.refusal()))));
        }
        if (!window.isEmpty()) {
            planned = planned.withNote(TEXTS.report().standbysReady(window.standbys(), plan.undertaking()));
            progress.accept(planned);
        }
        try {
            if (plan.announced() && !runner.countDown(request.id(), planned, plan.moving(), progress)) {
                return Runner.cancelled();
            }

            // After the countdown the run waits for the players to move, then stops regardless after ten seconds.
            final List<MessageRef> stillOn = choreography.waitUntilEmpty(moving);
            if (!stillOn.isEmpty()) {
                for (final MessageRef said : stillOn) {
                    planned = planned.withNote(said);
                }
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
            final UpdateReport started = steps.start(
                    new UpdateRun.Stopped(report, List.copyOf(starting), runtime),
                    plan.images(),
                    plan.remade(),
                    plan.pulls());
            report = steps.verify(started, starting, runner.waiting);
        }

        // Last, once the Minecraft services are healthy again, and postgres last of all.
        final UpdateReport foreign = ForeignImages.renewForeign(
                runner.containers, steps, report, plan.foreign(), plan.pulls(), progress, runner.waiting);
        // Last of all: the one-shot's run is over but for its row, which the agent it makes leaves alone.
        final UpdateReport renewed = runner.oneShot ? renewAgent(foreign) : foreign;

        final UpdateReport finished = Runner.settle(
                Runner.noteStandbys(renewed, choreography.close()),
                steps.unverifiedStops(),
                plan.undertaking(),
                plan.alreadyFailed() || done.failed(),
                plan.doubt());
        return finished.stage() == UpdateReport.Stage.FAILED
                ? Outcome.failed(UpdateReports.toJson(finished))
                : Outcome.done(UpdateReports.toJson(finished));
    }

    /** Makes the long-running steward-agent again at this one-shot's release, then waits for it to be healthy. */
    private UpdateReport renewAgent(final UpdateReport before) {
        UpdateReport report = before.with(new UpdateReport.ServiceLine(
                AgentWire.SERVICE,
                UpdateReport.State.STARTING,
                List.of(new UpdateReport.Change("image", null, "out of date")),
                TEXTS.report().renewingAgent()));
        progress.accept(report);
        final RedeployResult result = runner.containers.renewAgent();
        if (!result.triggered()) {
            report = report.with(
                    report.line(AgentWire.SERVICE).failed(TEXTS.report().agentNotRenewed(result.message())));
            progress.accept(report);
            return report;
        }
        return steps.verify(report, List.of(AgentWire.SERVICE), runner.waiting);
    }

    /** Starts back and fails the run when a service the plan stops did not stop and the kind refuses to go on. */
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
                stopped.report().withNote(TEXTS.report().notStopped(plan.undertaking(), notStopped)),
                stopped.services(),
                runtime));
        return Outcome.failed(UpdateReports.toJson(
                steps.verify(back, stopped.services(), runner.waiting).withStage(UpdateReport.Stage.FAILED)));
    }
}
