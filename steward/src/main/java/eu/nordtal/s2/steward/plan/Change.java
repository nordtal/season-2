package eu.nordtal.s2.steward.plan;

import eu.nordtal.s2.steward.source.RemoteFile;
import org.jspecify.annotations.Nullable;

/**
 * One artefact on one server, and what a run would do about it.
 *
 * @param service the compose service, or {@code null} for the bot's jar and the resource pack
 * @param installed what is there now: a jar's filename or the pack's short SHA-1; {@code null} when nothing is
 * @param wanted what the sources say is newest; {@code null} when a source could not be reached
 * @param note a sentence for a person, present exactly when the status needs explaining
 */
public record Change(
        @Nullable String service,
        String artifact,
        Status status,
        @Nullable String installed,
        @Nullable RemoteFile wanted,
        @Nullable String note) {

    public enum Status {
        /** What is installed is what the source says is newest. */
        UP_TO_DATE,
        /** A newer file exists; the only status that makes a run worth pressing. */
        OUTDATED,
        /**
         * Nothing with this artefact's filename prefix is installed, which on a running server may mean a renamed jar.
         */
        MISSING,
        /** The source could not be asked, so the run reports and stops rather than calling it unchanged. */
        UNRESOLVED,
        /** The service's volume is not mounted in this container, so nothing can be said about it. */
        MOUNT_MISSING,
        /**
         * The source has no build of this artefact for the network's Minecraft version, which is not a failure.
         *
         * The row stays in the report so the next run installs a build once one ships.
         */
        UNSUPPORTED,
        /** Our own release carries no file for this jar or the pack but one is installed, which stays: no failure. */
        NOT_IN_RELEASE;

        /** Whether a run would move a file for this row. */
        public boolean isWork() {
            return this == OUTDATED || this == MISSING;
        }

        /** Whether this row is a reason not to trust the rest of the report. */
        public boolean isFailure() {
            return this == UNRESOLVED || this == MOUNT_MISSING;
        }
    }

    public static Change unresolved(final @Nullable String service, final String artifact, final String why) {
        return new Change(service, artifact, Status.UNRESOLVED, null, null, why);
    }

    /**
     * No build of this artefact exists for the network's Minecraft version; a hand-installed jar shows as unclaimed.
     */
    public static Change unsupported(final @Nullable String service, final String artifact, final String why) {
        return new Change(service, artifact, Status.UNSUPPORTED, null, null, why);
    }
}
