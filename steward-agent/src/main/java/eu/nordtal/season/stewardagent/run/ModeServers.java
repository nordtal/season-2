package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.ServiceRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Which game server rests, read from the mode and the runtime.
 * At the agent's start, it stops the inactive mode's server and starts the active one.
 */
@Slf4j
final class ModeServers {

    /** How long the start waits for a server that a compose up created and has yet to start. */
    static final Duration COMPOSE_STARTS_WITHIN = Duration.ofMinutes(3);

    private static final Duration SETTLE_POLL = Duration.ofSeconds(5);

    /** Docker's statuses of a container that runs nothing and needs no stop. */
    private static final List<String> STOPPED = List.of("created", "exited", "dead");

    /** Docker's statuses of a container that compose or the restart policy is about to run. */
    private static final List<String> ABOUT_TO_RUN = List.of("created", "restarting");

    private ModeServers() {}

    /** Returns the active mode, empty when its row cannot be read. */
    static Optional<SeasonMode> active(final SeasonDirectory season) {
        try {
            final SeasonState state = season.state();
            return SeasonState.UNREAD.equals(state) ? Optional.empty() : Optional.of(state.mode());
        } catch (final RuntimeException unread) {
            log.warn("game mode unreadable, no server rested", unread);
            return Optional.empty();
        }
    }

    /**
     * Returns the inactive mode's server if it is stopped, which no run wakes.
     *
     * @param mode the active mode, empty when unknown, which leaves nothing resting
     */
    static List<String> resting(final Optional<SeasonMode> mode, final RuntimeResult runtime) {
        if (mode.isEmpty() || !runtime.reached()) {
            return List.of();
        }
        final String inactive = mode.get().other().server();
        return isRunning(runtime, inactive) ? List.of() : List.of(inactive);
    }

    static boolean isRunning(final RuntimeResult runtime, final String service) {
        return status(runtime, service).map("running"::equalsIgnoreCase).orElse(false);
    }

    /** Returns whether the service has no container or one that runs nothing, so a stop has nothing to do. */
    static boolean isStopped(final RuntimeResult runtime, final String service) {
        return status(runtime, service)
                .map(status -> STOPPED.stream().anyMatch(status::equalsIgnoreCase))
                .orElse(true);
    }

    /**
     * Stops the inactive mode's server and starts the active one, unless a take-down holds it.
     *
     * A one-shot that carries a run owns the servers, so the pass leaves them alone then. It never throws.
     */
    static void reconcile(final Runner runner) {
        try {
            if (runner.directory.handedTo().isPresent()) {
                log.info("game servers not changed, one-shot running");
                return;
            }
            final SeasonMode mode = active(runner.season()).orElse(null);
            if (mode == null) {
                return;
            }
            final RuntimeResult runtime =
                    settled(runner.containers, runner.waiting, mode.other().server());
            if (!runtime.reached()) {
                log.warn("runtime unreachable, game servers not changed: cause={}", Runner.unreachableMessage(runtime));
                return;
            }
            rest(runner.containers, runtime, mode.other().server());
            if (runner.directory.isHeld(mode.server())) {
                log.info("held server kept stopped: service={}", mode.server());
                return;
            }
            wake(runner.containers, runtime, mode.server());
        } catch (final RuntimeException failure) {
            log.error("mode servers not arranged", failure);
        }
    }

    /** Waits while a compose up has created the inactive server and not yet started it, for a bounded time. */
    private static RuntimeResult settled(final ContainerOps containers, final Waiting waiting, final String inactive) {
        final Instant deadline = waiting.now().plus(COMPOSE_STARTS_WITHIN);
        RuntimeResult runtime = containers.runtime();
        while (runtime.reached()
                && isAboutToRun(runtime, inactive)
                && waiting.now().isBefore(deadline)) {
            if (!waiting.sleep(SETTLE_POLL)) {
                break;
            }
            runtime = containers.runtime();
        }
        return runtime;
    }

    private static void rest(final ContainerOps containers, final RuntimeResult runtime, final String inactive) {
        final String id = containerOf(runtime, inactive);
        if (id == null || !isRunning(runtime, inactive)) {
            return;
        }
        final RedeployResult stopped = containers.stop(id);
        if (stopped.triggered()) {
            log.info("inactive mode server stopped: service={}", inactive);
        } else {
            log.warn("inactive mode server not stopped: service={} cause={}", inactive, stopped.message());
        }
    }

    private static void wake(final ContainerOps containers, final RuntimeResult runtime, final String active) {
        if (isRunning(runtime, active) || isAboutToRun(runtime, active)) {
            return;
        }
        final String id = containerOf(runtime, active);
        if (id == null) {
            log.warn("active mode server without container: service={}", active);
            return;
        }
        final RedeployResult started = containers.start(id);
        if (started.triggered()) {
            log.info("active mode server started: service={}", active);
        } else {
            log.warn("active mode server not started: service={} cause={}", active, started.message());
        }
    }

    private static boolean isAboutToRun(final RuntimeResult runtime, final String service) {
        return status(runtime, service)
                .map(status -> ABOUT_TO_RUN.stream().anyMatch(status::equalsIgnoreCase))
                .orElse(false);
    }

    private static Optional<String> status(final RuntimeResult runtime, final String service) {
        return runtime.service(service).map(ServiceRuntime::status);
    }

    private static @Nullable String containerOf(final RuntimeResult runtime, final String service) {
        return runtime.service(service).map(ServiceRuntime::containerId).orElse(null);
    }
}
