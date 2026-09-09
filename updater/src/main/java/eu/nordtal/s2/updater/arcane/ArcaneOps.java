package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;

/**
 * The calls an update sequence makes against Arcane, as a seam.
 *
 * <h2>Why an interface over a class with one implementation</h2>
 * The sequence behind these - read the runtime, stop each service, save a volume, start it again
 * and watch until it is healthy - is the part of this module with the most decisions in it and the
 * least chance of ever being rehearsed: it needs a real Arcane, a real project and a willingness to take
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

    /**
     * Which services are running an image the registry has moved past.
     *
     * <p>Read before anything is stopped, because an image update is a reason to take a server
     * down and therefore belongs in the plan a person confirms - not in a step discovered halfway
     * through a run that was counted down for something else.</p>
     */
    @NotNull ImageResult images();

    /**
     * Pulls one service's image and recreates its container from it.
     *
     * <p><b>This is not {@link #start}.</b> A start hands a stopped container back to Docker on
     * exactly the image it was created from, which is why an update of the jars alone never moves
     * an image: {@code entrypoint.sh}, the JRE underneath it and every change to {@code compose.yml}
     * stay on whatever was pulled at the last deploy. This asks Arcane for the one operation that
     * changes that, scoped to one service.</p>
     *
     * <p><b>The updater must never ask this for itself.</b> The recreate takes the container down,
     * and this sequence is running inside it - the same rule as {@link #stop}, for a sharper
     * reason: a stop it survives because it never asks for one, and a recreate would end the run in
     * the middle with the servers already down.</p>
     *
     * @param service the compose service name, not a container id - Arcane resolves it against the
     *                project, and a container id here is a 404
     */
    @NotNull RedeployResult recreate(@NotNull String service);

    /**
     * Asks Arcane to snapshot one volume, and answers with the backup's id.
     *
     * <p>Started is not saved: the POST answers 202 and the work happens on Arcane's side. What
     * follows is {@link #backupState} until it settles or the run's patience runs out.</p>
     */
    @NotNull BackupResult backup(@NotNull String volume);

    /**
     * Where one started snapshot has got to.
     *
     * <p>An unreadable answer is {@link BackupResult.Status#RUNNING} and never a failure - the
     * snapshot is still happening on the far side, and calling it failed here would start the
     * servers back up on top of a half-written one.</p>
     *
     * @param backupId what {@link #backup} handed back
     */
    @NotNull BackupResult backupState(@NotNull String volume, @NotNull String backupId);
}
