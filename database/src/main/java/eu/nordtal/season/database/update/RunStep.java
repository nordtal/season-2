package eu.nordtal.season.database.update;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The typed values of the steps of a run, as {@code run_step} and its fact tables hold them.
 * steward-agent writes a step when it begins and ends it with an outcome and, if it went wrong, a reason.
 */
public final class RunStep {

    private RunStep() {}

    /** What a step does, in the order that a run reaches the steps. */
    public enum Kind {
        /** Reads the runtime of the project before anything is planned. */
        RUNTIME,
        /** What the request covers and what it leaves out. */
        SCOPE,
        /** What the sources answered: the artefacts per service, and the release from and to for the run. */
        RESOLVE,
        /** A temporary container of another release takes over the run. */
        HAND_OVER,
        /** Checks whether a backup leaves the disk its free share. */
        ROOM_CHECK,
        /** Removes the archives that retention or the disk budget no longer keeps. */
        PRUNE,
        DUMP_DATABASE,
        STANDBY_UP,
        COUNTDOWN,
        PLAYERS_MOVED,
        STOP,
        SAVE_VOLUME,
        /** A take-down holds its service down, so no run starts it. */
        HOLD,
        /** A start releases the hold of its service. */
        UNHOLD,
        MIGRATE,
        /** Puts files into place or removes them, or recreates a container from its image. */
        INSTALL,
        /** Puts an archive back into its volume or the database. */
        RESTORE,
        START,
        /** Waits for a started service to report healthy, with the version seen. */
        VERIFY,
        PLAYERS_BACK,
        STANDBY_DOWN,
        /** Removes the images that nothing uses. */
        CLEANUP,
        OFFSITE_COPY
    }

    /** How a step ended. */
    public enum Outcome {
        DONE,
        /** Left out on purpose, or nothing to do. */
        SKIPPED,
        /** Needs a look, but failed nothing. */
        WARNING,
        FAILED
    }

    /** Why a step did not go as asked. A number it names is one of the facts of the step. */
    public enum Reason {
        /** The runtime was unreachable. */
        UNREACHABLE,
        /** steward-agent failed. Its log says how. */
        UNEXPECTED,
        CANCELLED,
        /** steward-agent stopped while it waited. */
        INTERRUPTED,
        NO_CONTAINER,
        /** Docker or the process refused. The run log has the answer. */
        REFUSED,
        /** Not healthy between the start and the end of the step. */
        NOT_HEALTHY,
        /** It stopped and nobody could read how it ended. */
        UNVERIFIED_STOP,
        PLAYERS_STAYED,
        PLAYERS_UNKNOWN,
        /** The row of the room check says how much was needed and free. */
        NO_ROOM,
        TOOL_FAILED,
        NO_REPOSITORY,
        REFUSED_LOCAL_BUILD,
        RELEASED_MEANWHILE,
        OLDER_RELEASE,
        HELD,
        NOT_IN_SCOPE,
        NOTHING_HELD,
        /** The request names nothing to act on. */
        UNNAMED,
        /** Steward or postgres, which a take-down never stops. */
        NEVER_DOWN,
        /** steward-agent, which never recreates itself. */
        SELF,
        /** The request names something that this stack does not have. */
        UNKNOWN,
        NOT_A_VOLUME,
        NO_VOLUMES,
        NOTHING_TO_REMOVE,
        /** The backup before a restore was not saved, so nothing was put back. */
        NOT_SAVED,
        /** Half was put back, so what runs on it stays down. */
        LEFT_DOWN,
        SOURCE_FAILED,
        NOT_IN_RELEASE,
        /** A jar that no artefact claims. */
        UNCLAIMED,
        /** An image whose registry could not be compared. */
        UNVERIFIABLE,
        LOCAL_BUILD,
        FOREIGN_NEWER,
        /** The newest Velocity is newer than the API the proxy is built against. */
        VELOCITY_AHEAD,
        /** A service's own secrets file lacks a value, which ./nordtal.sh update adds; the step names each. */
        MISSING_SECRET
    }

    /** Which image a container was recreated from. */
    public enum Image {
        PULLED,
        KEPT
    }

    /** Why a prune removed an archive. */
    public enum PruneRule {
        /** No daily, weekly or monthly rule keeps it any longer. */
        RETENTION,
        /** The archives took more than their share of the disk, and it was among the oldest. */
        OVER_BUDGET
    }

    /**
     * One step as read back, with every fact written for it.
     *
     * @param started {@code null} for a step of a plan, which no run has begun
     * @param exitCode what the tool exited with, for a step that failed on it
     * @param cacheFreed the bytes a cleanup freed in the build cache
     * @param saved the archives the step wrote
     * @param pruned the archives the step removed
     */
    public record Row(
            int seq,
            Kind step,
            @Nullable String service,
            @Nullable Instant started,
            @Nullable Instant finished,
            @Nullable Outcome outcome,
            @Nullable Reason reason,
            @Nullable Integer exitCode,
            List<Change> changes,
            List<Move> players,
            List<ImageRemoved> imagesRemoved,
            @Nullable Long cacheFreed,
            @Nullable Room room,
            @Nullable Offsite offsite,
            List<Archive> saved,
            List<Archive> pruned) {}

    /** An artefact a step resolves, installs or sees. */
    public record Change(
            String artefact,
            @Nullable String from,
            @Nullable String to,
            @Nullable Image image) {}

    /** Players moved off a service, and how many came back. */
    public record Move(String from, String to, int moved, int returned) {}

    public record ImageRemoved(String image, long bytes) {}

    public record Room(long needed, long free) {}

    public record Offsite(String snapshot, long bytesAdded) {}

    /**
     * An archive of the catalogue.
     *
     * @param volume the volume it holds, or {@code database} for a dump
     * @param stopVerified false when a service it holds stopped and nobody could read how it ended
     * @param rule why it was pruned, {@code null} while it is kept
     */
    public record Archive(
            String volume,
            String file,
            long bytes,
            long tookMillis,
            boolean stopVerified,
            @Nullable PruneRule rule) {}
}
