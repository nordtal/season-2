package eu.nordtal.season.smp.stage;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one {@link Cinematic} per player at a time.
 * A second start for the same player returns {@code false}. {@link #cancel} clears the screen once.
 */
public final class Cinematics {

    private static final class Run {

        private final List<Scheduler.Task> handles;
        private final CinematicStage stage;

        Run(final List<Scheduler.Task> handles, final CinematicStage stage) {
            this.handles = handles;
            this.stage = stage;
        }

        List<Scheduler.Task> handles() {
            return handles;
        }

        CinematicStage stage() {
            return stage;
        }
    }

    private final Scheduler scheduler;

    private final Map<UUID, Run> running = new ConcurrentHashMap<>();

    public Cinematics(final Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Starts a staging unless the player already has one.
     * The first frame, the sound and the effect happen in this call.
     *
     * @return whether this call started the staging
     */
    public boolean start(final UUID who, final Cinematic cinematic, final CinematicStage stage) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(cinematic, "cinematic");
        Objects.requireNonNull(stage, "stage");

        final Run run = new Run(new ArrayList<>(), stage);
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
            run.handles().add(scheduler.after(PaperScheduler.TICK.multipliedBy(cue.atTick()), () -> {
                // A frame after a cancel shows nothing.
                if (running.get(who) == run) {
                    stage.show(
                            cue.frame().image(),
                            cinematic.subtitle(),
                            cue.frame().ticks());
                }
            }));
        }
        run.handles().add(scheduler.after(PaperScheduler.TICK.multipliedBy(total), () -> finish(who, run)));
        return true;
    }

    public boolean isRunning(final UUID who) {
        return running.containsKey(who);
    }

    public void cancel(final UUID who) {
        final Run run = running.remove(who);
        if (run != null) {
            stop(run);
        }
    }

    public void cancelAll() {
        for (final UUID who : List.copyOf(running.keySet())) {
            cancel(who);
        }
    }

    private void finish(final UUID who, final Run run) {
        if (running.remove(who, run)) {
            stop(run);
        }
    }

    private static void stop(final Run run) {
        for (final Scheduler.Task task : run.handles()) {
            task.cancel();
        }
        run.stage().clear();
    }
}
