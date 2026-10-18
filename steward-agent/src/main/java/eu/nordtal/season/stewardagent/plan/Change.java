package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.source.RemoteFile;
import org.jspecify.annotations.Nullable;

/**
 * One artefact on one server, and what a run would do about it.
 *
 * @param service the compose service, {@code null} for the jar of the bot and the resource pack
 * @param installed a jar's filename or the short SHA-1 of the pack, {@code null} when nothing is installed
 * @param wanted what the sources call newest, {@code null} when a source could not be reached
 * @param reason why, present exactly when the status needs explaining
 */
public record Change(
        @Nullable String service,
        String artifact,
        Status status,
        @Nullable String installed,
        @Nullable RemoteFile wanted,
        @Nullable MessageRef reason) {

    public enum Status {
        /** The installed file is the newest. */
        UP_TO_DATE,
        /** A newer file exists. Only this status makes a run worth starting. */
        OUTDATED,
        /**
         * Nothing with this artefact's filename prefix is installed, which on a running server may mean a renamed jar.
         */
        MISSING,
        /** The source could not be asked. */
        UNRESOLVED,
        /** The volume of the service is not mounted in this container. */
        MOUNT_MISSING,
        /**
         * The source has no build of the artefact for the Minecraft version of the network. The row stays.
         */
        UNSUPPORTED,
        /** Our release has no file for this jar or the pack, and an installed one stays. */
        NOT_IN_RELEASE;

        public boolean isWork() {
            return this == OUTDATED || this == MISSING;
        }

        public boolean isFailure() {
            return this == UNRESOLVED || this == MOUNT_MISSING;
        }
    }

    public static Change unresolved(final @Nullable String service, final String artifact, final MessageRef why) {
        return new Change(service, artifact, Status.UNRESOLVED, null, null, why);
    }

    public static Change unsupported(final @Nullable String service, final String artifact, final MessageRef why) {
        return new Change(service, artifact, Status.UNSUPPORTED, null, null, why);
    }
}
