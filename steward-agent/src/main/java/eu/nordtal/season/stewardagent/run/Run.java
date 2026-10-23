package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The sequence every kind of run goes through. A kind only plans, see {@link Kinds}.
 *
 * Open the standbys, count down, evacuate, stop, carry out the kind's payload, start, verify, close and settle.
 */
final class Run {

    private final Runner runner;
    private final UpdateRun steps;
    private final RuntimeResult runtime;
    private final Consumer<RunStage> progress;

    Run(final Runner runner, final UpdateRun steps, final RuntimeResult runtime, final Consumer<RunStage> progress) {
        this.runner = runner;
        this.steps = steps;
        this.runtime = runtime;
        this.progress = progress;
    }

    /**
     * The work between the stop and the start.
     *
     * Throwing {@link Abort} starts everything again and fails the run.
     */
    @FunctionalInterface
    interface Payload {

        Payload NONE = (steps, stopped) -> new Done(false);

        Done carryOut(UpdateRun steps, UpdateRun.Stopped stopped);
    }

    /**
     * What a payload left behind.
     *
     * @param failed whether something failed that no step of the run shows
     * @param afterwards what the run does once everything it stopped is back
     * @param leftDown services the run stopped and does not start again, because their data is incomplete
     */
    record Done(boolean failed, Runnable afterwards, List<String> leftDown) {

        Done {
            leftDown = List.copyOf(leftDown);
        }

        Done(final boolean failed) {
            this(failed, () -> {});
        }

        Done(final boolean failed, final Runnable afterwards) {
            this(failed, afterwards, List.of());
        }
    }

    /** Ends a payload early: everything stopped is started again and the run fails. */
    static final class Abort extends RuntimeException {

        Abort() {
            super(null, null, false, false);
        }
    }

    /**
     * The plan of a kind.
     *
     * @param moving the services the run stops, in that order
     * @param work the artefacts each service is given, whose versions its verify step names
     * @param announced whether the run counts down, so the proxy and the feeds warn first
     * @param stops whether the run stops what it plans, which a start does not
     * @param payload the work while the servers are down
     * @param startsAgain whether the run starts what it stopped, which only a take-down does not
     * @param alsoStarts services the run starts although it did not stop them, as the release of a hold does
     * @param images the services to recreate as they start, because their container is out of date
     * @param foreign the images nobody here builds, recreated once the rest is back and announced like a stop
     * @param refusesUnstopped whether a service that did not stop ends the run before its payload
     * @param remade services whose container is created again as they start
     * @param pulls whether recreating a container pulls its image first
     */
    record Plan(
            List<String> moving,
            Map<String, List<RunStep.Change>> work,
            boolean announced,
            boolean stops,
            Payload payload,
            boolean startsAgain,
            List<String> alsoStarts,
            ImageResult images,
            List<String> foreign,
            boolean refusesUnstopped,
            Runner.Doubt doubt,
            boolean alreadyFailed,
            List<String> remade,
            boolean pulls) {

        Plan {
            moving = List.copyOf(moving);
            work = Map.copyOf(work);
            alsoStarts = List.copyOf(alsoStarts);
            foreign = List.copyOf(foreign);
            remade = List.copyOf(remade);
        }

        static Plan of(
                final List<String> moving,
                final Payload payload,
                final boolean refusesUnstopped,
                final Runner.Doubt doubt) {
            return new Plan(
                    moving,
                    Map.of(),
                    true,
                    true,
                    payload,
                    true,
                    List.of(),
                    ImageResult.of(Map.of()),
                    List.of(),
                    refusesUnstopped,
                    doubt,
                    false,
                    List.of(),
                    true);
        }

        Plan announced(final boolean warned) {
            return new Plan(
                    moving,
                    work,
                    warned,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
        }

        Plan stoppingNothing() {
            return new Plan(
                    moving,
                    work,
                    false,
                    false,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
        }

        Plan leavingThemDown() {
            return new Plan(
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    false,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
        }

        Plan alsoStarting(final List<String> services) {
            return new Plan(
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    services,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls);
        }

        Plan renewing(
                final Map<String, List<RunStep.Change>> given,
                final ImageResult newer,
                final List<String> renewed,
                final boolean failed) {
            return new Plan(
                    moving,
                    given,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    newer,
                    renewed,
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
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    theirs,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    ours,
                    pulling);
        }

        /**
         * Returns what the countdown names: every stopped service and every recreated foreign image.
         */
        List<String> named() {
            final Set<String> named = new LinkedHashSet<>(moving);
            named.addAll(foreign);
            return List.copyOf(named);
        }
    }

    /** Carries the plan out. It never throws, so every stop ends in a start. */
    Outcome carryOut(final UpdateRequest request, final Plan plan) {
        final Choreography choreography =
                new Choreography(runner.containers, runner.occupancy(), runner.waiting, steps.record);
        final List<String> moving = plan.stops() ? plan.moving() : List.of();
        if (!choreography.open(moving).opened()) {
            return Outcome.failed();
        }
        try {
            final Steps.Step players =
                    plan.announced() ? runner.countDown(request.id(), plan.named(), progress, steps.record) : null;
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

            final UpdateRun.Stopped stopped =
                    plan.stops() ? steps.stop(moving, runtime) : new UpdateRun.Stopped(List.of(), runtime);
            if (plan.refusesUnstopped() && !stopped.services().containsAll(moving)) {
                return startAgain(stopped);
            }

            final Done done;
            try {
                done = plan.payload().carryOut(steps, stopped);
            } catch (final Abort abort) {
                // Started again before the run settles, so a failed payload never leaves the network stopped.
                return startAgain(stopped);
            }

            return finish(plan, stopped, done, choreography, players != null);
        } finally {
            choreography.close();
        }
    }

    private Outcome startAgain(final UpdateRun.Stopped stopped) {
        steps.start(stopped);
        steps.verify(stopped.services(), runner.waiting);
        return Outcome.failed();
    }

    private Outcome finish(
            final Plan plan,
            final UpdateRun.Stopped stopped,
            final Done done,
            final Choreography choreography,
            final boolean moved) {
        if (plan.startsAgain()) {
            final List<String> starting = new ArrayList<>(stopped.services());
            plan.alsoStarts().stream()
                    .filter(service -> !starting.contains(service))
                    .forEach(starting::add);
            starting.removeAll(done.leftDown());
            final UpdateRun.Stopped back = new UpdateRun.Stopped(List.copyOf(starting), runtime);
            steps.start(back, plan.images(), plan.remade(), plan.pulls());
            steps.verify(starting, runner.waiting, plan.work());
        }

        // The proxy counts the players back. The standbys give theirs back as they close.
        final Steps.Step returning = moved ? steps.record.begin(RunStep.Kind.PLAYERS_BACK) : null;
        // The standbys close first, because caddy publishes their ports.
        choreography.close();
        if (returning != null) {
            returning.done();
        }
        // This runs once the Minecraft services are healthy, with postgres last.
        ForeignImages.renewForeign(runner.containers, steps, plan.foreign(), plan.pulls(), runner.waiting);
        done.afterwards().run();
        // Last of all, since the agent it creates leaves the row alone.
        if (runner.oneShot) {
            renewAgent();
        }
        return Outcome.at(Runner.settle(
                steps.record, steps.unverifiedStops(), plan.alreadyFailed() || done.failed(), plan.doubt()));
    }

    /**
     * Creates the long-running steward-agent at the release of this one-shot and waits for its health.
     */
    private void renewAgent() {
        final Steps.Step install = steps.record
                .begin(RunStep.Kind.INSTALL, AgentWire.SERVICE)
                .change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED);
        final RedeployResult result = runner.containers.renewAgent();
        UpdateRun.endBy(install, result);
        if (result.triggered()) {
            steps.verify(List.of(AgentWire.SERVICE), runner.waiting);
        }
    }
}
