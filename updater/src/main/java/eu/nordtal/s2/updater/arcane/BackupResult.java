package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One volume's snapshot, as Arcane last described it.
 *
 * <p>Started, still running, finished, or refused before it began - and the one thing that is
 * <em>not</em> a state of its own is "we could not read it this time". A poll that fails is
 * {@link Status#RUNNING} with the reason in {@code message}: the snapshot is still happening on the
 * far side and giving up on a single unreadable poll would start the servers back up on top of it.
 * Only the patience in {@code updater.yml#backup.patience-minutes} ends a wait.</p>
 *
 * @param status  where it has got to
 * @param id      Arcane's id for the backup, which is what the next poll asks about. {@code null}
 *                when nothing was started
 * @param message what to tell a person - the failure, the refusal, or Arcane's own error text
 */
public record BackupResult(@NotNull Status status, @Nullable String id, @NotNull String message) {

    /** What a snapshot is doing. Deliberately not a copy of Arcane's own vocabulary. */
    public enum Status {

        /** Arcane accepted the request and the snapshot is in flight. */
        RUNNING,
        /** Arcane says it finished. */
        SUCCEEDED,
        /** Arcane says it failed, and {@code message} is why. */
        FAILED,
        /**
         * It never started: Arcane is unconfigured, unreachable, or answered an error to the POST.
         *
         * <p>Kept apart from {@link #FAILED} because the two need different sentences. A failed
         * snapshot means Arcane tried; a refused one means the run never got that far, and the
         * thing to look at is the configuration rather than the volume.</p>
         */
        REFUSED
    }

    public static BackupResult running(final @NotNull String id, final @NotNull String message) {
        return new BackupResult(Status.RUNNING, id, message);
    }

    public static BackupResult succeeded(final @NotNull String id, final @NotNull String message) {
        return new BackupResult(Status.SUCCEEDED, id, message);
    }

    public static BackupResult failed(final @Nullable String id, final @NotNull String message) {
        return new BackupResult(Status.FAILED, id, message);
    }

    public static BackupResult refused(final @NotNull String message) {
        return new BackupResult(Status.REFUSED, null, message);
    }

    /** @return whether this snapshot has stopped moving, one way or the other */
    public boolean isFinished() {
        return status != Status.RUNNING;
    }

    /** @return whether this snapshot ended the way it was supposed to */
    public boolean isGood() {
        return status == Status.SUCCEEDED;
    }
}
