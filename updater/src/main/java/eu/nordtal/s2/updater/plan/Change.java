package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.updater.source.RemoteFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One artefact on one server, and what a run would do about it.
 *
 * @param service   the compose service, or {@code null} for something that lives outside the four
 *                  Minecraft volumes - the bot's jar and the resource pack.
 * @param installed what is there now, in whatever form identifies it: a filename for a jar, the
 *                  first characters of the SHA-1 for the pack. {@code null} when nothing is.
 * @param wanted    what the sources say is newest. {@code null} when a source could not be reached
 *                  at all, which is the one case where the report must not read as "nothing to do".
 * @param note      a sentence for a person, present exactly when the status needs explaining.
 */
public record Change(@Nullable String service,
                     @NotNull String artifact,
                     @NotNull Status status,
                     @Nullable String installed,
                     @Nullable RemoteFile wanted,
                     @Nullable String note) {

    public enum Status {
        /** What is installed is what the source says is newest. Nothing to do. */
        UP_TO_DATE,
        /** A newer file exists. This is the only status that makes a run worth pressing. */
        OUTDATED,
        /**
         * Nothing with this artefact's filename prefix is installed. On a fresh volume that is
         * every row and is entirely normal; on a running server it is either a first deployment of
         * something new, or - the case worth catching - a publisher that has changed the jar's
         * name, which makes the old jar an {@link UpdatePlan#unclaimed() unclaimed} one at the
         * same time.
         */
        MISSING,
        /**
         * The source could not be asked. The run reports and stops rather than treating an
         * unreachable API as "unchanged": the difference between "up to date" and "unknown" is the
         * whole value of the report.
         */
        UNRESOLVED,
        /** The service's volume is not mounted in this container, so nothing can be said about it. */
        MOUNT_MISSING,
        /**
         * The source answered and has no build of this artefact for the Minecraft version the
         * network runs. Nothing is installed, nothing is skipped, and nothing failed.
         *
         * <h2>Why this is not UNRESOLVED, which is where it used to land</h2>
         * {@link #isFailure()} is what makes {@code Applier} skip a service <em>whole</em> - the
         * all-or-nothing rule that keeps a server from coming up on half a set of plugins. A
         * plugin that is simply behind the platform would therefore have blocked the season jar
         * beside it on every run, for weeks, for a reason nobody here can act on.
         *
         * <h2>And it is not UP_TO_DATE either</h2>
         * Nothing is installed, so there is nothing to be up to date. The point of carrying the row
         * at all is that the artefact stays <b>named</b> in the report while it waits: the day its
         * publisher ships a build for this version, the next run installs it and no code changes.
         * An artefact quietly dropped from the plan is one somebody has to remember.
         */
        UNSUPPORTED;

        /** Whether a run would move a file for this row. */
        public boolean isWork() {
            return this == OUTDATED || this == MISSING;
        }

        /** Whether this row is a reason not to trust the rest of the report. */
        public boolean isFailure() {
            return this == UNRESOLVED || this == MOUNT_MISSING;
        }
    }

    public static @NotNull Change unresolved(final @Nullable String service, final @NotNull String artifact,
                                             final @NotNull String why) {
        return new Change(service, artifact, Status.UNRESOLVED, null, null, why);
    }

    /**
     * No build of this artefact exists for the Minecraft version the network runs.
     *
     * <p>{@link #installed()} is always {@code null} here, and not because nothing can be lying
     * there. Nothing was <em>resolved</em>, so there is no filename to match a jar against - and a
     * jar somebody installed by hand comes out in {@link UpdatePlan#unclaimed()} instead, which is
     * where every file this plan does not account for goes and is louder than a version comparison
     * would be.</p>
     */
    public static @NotNull Change unsupported(final @Nullable String service, final @NotNull String artifact,
                                              final @NotNull String why) {
        return new Change(service, artifact, Status.UNSUPPORTED, null, null, why);
    }
}
