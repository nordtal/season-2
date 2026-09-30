package eu.nordtal.s2.smp.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a {@link Cinematic} for one player at a time and can stop it.
 *
 * A second staging for the same player is refused rather than queued; {@link #cancel} clears the screen exactly once.
 */
public final class Cinematics {

    /** A task to run later, and a way to stop it before it does. */
    public interface Handle {

        /** Stops the task if it has not run. */
        void cancel();
    }

    /** Schedules a task {@code delayTicks} ticks ahead; a delay of 0 may run now or on the next tick. */
    public interface Scheduler {

        Handle later(Runnable task, long delayTicks);
    }

    /** One player's run, compared by identity so two runs are never equal. */
    private static final class Run {

        private final List<Handle> handles;
        private final CinematicStage stage;

        Run(final List<Handle> handles, final CinematicStage stage) {
            this.handles = handles;
            this.stage = stage;
        }

        List<Handle> handles() {
            return handles;
        }

        CinematicStage stage() {
            return stage;
        }
    }

    private final Scheduler scheduler;

    /** Concurrent because a cancel arrives from wherever a player leaves. */
    private final Map<UUID, Run> running = new ConcurrentHashMap<>();

    public Cinematics(final Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Starts a staging unless this player already has one; the first frame, sound and effect happen in this call.
     *
     * @return whether this call started it
     */
    public boolean start(final UUID who, final Cinematic cinematic, final CinematicStage stage) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(cinematic, "cinematic");
        Objects.requireNonNull(stage, "stage");

        final Run run = new Run(new ArrayList<>(), stage);
        // putIfAbsent: the check and the claim are one step.
        if (running.putIfAbsent(who, run) != null) {
            return false;
        }

        final int total = cinematic.totalTicks();
        if (cinematic.sound() != null) {
            stage.play(cinematic.sound());
        }
        if (cinematic.effect() != null) {
            stage.effect(cinematic.effect(), total);
        }

        final List<Cinematic.Cue> cues = cinematic.cues();
        stage.show(
                cues.getFirst().frame().image(),
                cinematic.subtitle(),
                cues.getFirst().frame().ticks());
        for (final Cinematic.Cue cue : cues.subList(1, cues.size())) {
            run.handles()
                    .add(scheduler.later(
                            () -> {
                                // A cancel may have cleared the screen; a late frame must not reopen it.
                                if (running.get(who) == run) {
                                    stage.show(
                                            cue.frame().image(),
                                            cinematic.subtitle(),
                                            cue.frame().ticks());
                                }
                            },
                            cue.atTick()));
        }
        run.handles().add(scheduler.later(() -> finish(who, run), total));
        return true;
    }

    /** Returns whether this player is in the middle of a staging. */
    public boolean isRunning(final UUID who) {
        return running.containsKey(who);
    }

    /** Stops this player's staging, if any, and clears their screen. */
    public void cancel(final UUID who) {
        final Run run = running.remove(who);
        if (run != null) {
            stop(run);
        }
    }

    /** Stops every staging, for a plugin's disable. */
    public void cancelAll() {
        for (final UUID who : List.copyOf(running.keySet())) {
            cancel(who);
        }
    }

    /** Ends a run at its last frame, the same as a cancel from the screen's point of view. */
    private void finish(final UUID who, final Run run) {
        if (running.remove(who, run)) {
            stop(run);
        }
    }

    private static void stop(final Run run) {
        for (final Handle handle : run.handles()) {
            handle.cancel();
        }
        run.stage().clear();
    }
}
