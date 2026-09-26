package eu.nordtal.s2.common.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a {@link Cinematic} for one player at a time and can stop it.
 *
 * <b>The whole decision half, with no platform in it</b>
 *
 * What ticks a frame lands on, what happens when a second staging is asked for while one is running,
 * and what a cancel has to undo - none of that needs a server, and all of it is the part that goes
 * wrong. The two things that <em>do</em> need one are behind {@link Scheduler} and
 * {@link CinematicStage}, which is what lets a test drive the whole run with a fake clock.
 *
 * <b>One staging per player, and the second is refused rather than queued</b>
 *
 * Two stagings at once means two title sequences fighting over one screen and two effects with two
 * end times, the later of which would clear the earlier one's blindness while it is still meant to
 * be running. Refusing is also the honest answer for the caller: the moments this is for are each
 * "once, at a particular time", so an overlap is a bug somewhere else and swallowing it would hide
 * that.
 *
 * <b>A cancel is not a failure</b>
 *
 * A player who leaves or dies mid-staging is the ordinary case, not an error: {@link #cancel} stops
 * the outstanding frames and calls {@link CinematicStage#clear()} exactly once, which is what
 * guarantees nobody is left blind. It is safe to call for a player who has nothing running.
 */
public final class Cinematics {

    /** A task to run later, and a way to stop it before it does. */
    public interface Handle {

        /** Stops the task if it has not run. Called at most once per handle by this class. */
        void cancel();
    }

    /**
     * Somewhere to put a task that should happen in {@code delayTicks} ticks.
     *
     * One method, because that is genuinely all a staging needs. On Paper this is
     * {@code BukkitScheduler#runTaskLater}; in a test it is a list.
     *
     * @implSpec a task submitted with a delay of 0 may run immediately or on the next tick; nothing
     * here depends on which, because the first frame is shown directly rather than scheduled.
     */
    public interface Scheduler {

        Handle later(Runnable task, long delayTicks);
    }

    /**
     * One player's run: everything outstanding, and the surface to clean up.
     *
     * Deliberately not a record: {@link #start} and {@link #finish} tell two runs apart by
     * identity, not by the equality a record would derive from its fields, so this relies on the
     * identity {@link Object#equals} gives every plain class.
     */
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

    /**
     * Concurrent because a cancel arrives from wherever a player leaves.
     *
     * On Paper every caller is on the main thread and this map could be plain - but a class in
     * {@code :common} has no way to insist on that, and the cost of being right anyway is one map.
     */
    private final Map<UUID, Run> running = new ConcurrentHashMap<>();

    public Cinematics(final Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Starts one, unless this player already has one.
     *
     * The first frame, the sound and the effect all happen <em>now</em>, inside this call. Only
     * the frames after the first and the final clean-up are scheduled - so a staging whose sequence
     * is a single frame needs the scheduler for nothing but its end, and a caller that is already on
     * the right thread sees the moment begin without a tick's delay.
     *
     * @return whether this call started it. {@code false} means one was already running, and the
     * caller has learned something rather than nothing
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

    /** Whether this player is in the middle of one. */
    public boolean isRunning(final UUID who) {
        return running.containsKey(who);
    }

    /**
     * Stops this player's staging, if any, and clears their screen.
     *
     * Wired to leaving and to dying: both are moments where the frames are suddenly about
     * somebody who is not there, and the blindness would outlive them.
     */
    public void cancel(final UUID who) {
        final Run run = running.remove(who);
        if (run != null) {
            stop(run);
        }
    }

    /**
     * Stops every staging - what a plugin calls at disable.
     *
     * Without it, a shutdown in the middle of a welcome leaves the effect on a player who is
     * about to be saved to disk with it, and they come back blind to a server that has forgotten
     * why.
     */
    public void cancelAll() {
        for (final UUID who : List.copyOf(running.keySet())) {
            cancel(who);
        }
    }

    /** The natural end. Identical to a cancel from the screen's point of view. */
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
