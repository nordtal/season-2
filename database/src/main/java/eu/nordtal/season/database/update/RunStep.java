package eu.nordtal.season.database.update;

/**
 * The typed values of a run's steps, as {@code run_step} and its fact tables hold them.
 *
 * steward-agent writes a step when it begins and ends it with an outcome and, unless it went as asked, a reason.
 */
public final class RunStep {

    private RunStep() {}

    /** What a step does, in the order a run reaches them. */
    public enum Kind {
        /** Reading the project's runtime before anything is planned. */
        RUNTIME,
        /** What the request covers, and what it leaves out. */
        SCOPE,
        /** What the sources answered: per service its artefacts, for the run the release from and to. */
        RESOLVE,
        /** The run handed to a one-shot at another release. */
        HAND_OVER,
        /** Whether a backup leaves the disk its free share. */
        ROOM_CHECK,
        /** Archives the retention or the disk budget no longer keeps, removed. */
        PRUNE,
        DUMP_DATABASE,
        STANDBY_UP,
        COUNTDOWN,
        PLAYERS_MOVED,
        STOP,
        SAVE_VOLUME,
        /** A take-down's service held down, so no run starts it. */
        HOLD,
        /** A start's service let go of its hold. */
        UNHOLD,
        MIGRATE,
        /** Files put into place or removed, or a container made again from its image. */
        INSTALL,
        /** An archive put back into its volume or the database. */
        RESTORE,
        START,
        /** Waiting for a started service to report healthy, with the version seen. */
        VERIFY,
        PLAYERS_BACK,
        STANDBY_DOWN,
        /** The images nothing uses any more, removed. */
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

    /** Why a step did not go as asked; any number it names is one of the step's own facts. */
    public enum Reason {
        /** The runtime could not be reached. */
        UNREACHABLE,
        /** steward-agent itself failed; its log says how. */
        UNEXPECTED,
        CANCELLED,
        /** steward-agent stopped while it waited. */
        INTERRUPTED,
        NO_CONTAINER,
        /** Docker or the process asked refused; the run log has its answer. */
        REFUSED,
        /** Not healthy between the step's start and end. */
        NOT_HEALTHY,
        /** It stopped, and nobody could read how it ended. */
        UNVERIFIED_STOP,
        PLAYERS_STAYED,
        PLAYERS_UNKNOWN,
        /** The room check's own row says how much was needed and free. */
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
        /** steward-agent, which never makes its own container again. */
        SELF,
        /** The request names something this stack does not have. */
        UNKNOWN,
        NOT_A_VOLUME,
        NO_VOLUMES,
        NOTHING_TO_REMOVE,
        /** The backup before a restore was not saved, so nothing was put back. */
        NOT_SAVED,
        /** Put back half, so what runs on it stays held down. */
        LEFT_DOWN,
        SOURCE_FAILED,
        NOT_IN_RELEASE,
        /** A jar no artefact claims. */
        UNCLAIMED,
        /** An image whose registry could not be compared. */
        UNVERIFIABLE,
        LOCAL_BUILD,
        FOREIGN_NEWER
    }

    /** Which image a container was made again from. */
    public enum Image {
        PULLED,
        KEPT
    }
}
