package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;

/**
 * The three calls an update sequence makes against Arcane, as a seam.
 *
 * <h2>Why an interface over a class with one implementation</h2>
 * The sequence behind these three - read the runtime, stop each service, start it again, watch
 * until it is healthy - is the part of this module with the most decisions in it and the least
 * chance of ever being rehearsed: it needs a real Arcane, a real project and a willingness to take
 * the network down. Nobody has seen a 2xx from Arcane yet at all. So the ordering, the refusals and
 * the timeout are held by tests against a fake, and {@link Arcane} remains the only thing that
 * speaks HTTP.
 */
public interface ArcaneOps {

    /** Every service of the project with its container id, status and health. */
    @NotNull RuntimeResult runtime();

    /** Stops one container. See {@link Arcane#stop} for the thirty-second caveat. */
    @NotNull RedeployResult stop(@NotNull String containerId);

    /** Starts one container again. Started is not back - {@link #runtime()} answers that. */
    @NotNull RedeployResult start(@NotNull String containerId);
}
