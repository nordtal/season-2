package eu.nordtal.season.database.update;

import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an update run has done so far, as data each surface draws for itself.
 *
 * @param stage    where the run has got to
 * @param services one line per service the run touches, in reading order
 * @param notes    what the run did or found beside its lines, one record each, in the order they happened
 */
public record UpdateReport(Stage stage, List<ServiceLine> services, List<Note> notes) {

    public UpdateReport {
        Objects.requireNonNull(stage, "stage");
        services = List.copyOf(services == null ? List.of() : services);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /** Returns an empty report at a stage, for the first write of a run. */
    public static UpdateReport at(final Stage stage) {
        return new UpdateReport(stage, List.of(), List.of());
    }

    public UpdateReport withStage(final Stage next) {
        return new UpdateReport(next, services, notes);
    }

    public UpdateReport withNote(final Note note) {
        return withNotes(List.of(note));
    }

    public UpdateReport withNotes(final List<Note> more) {
        final List<Note> combined = new ArrayList<>(notes);
        combined.addAll(more);
        return new UpdateReport(stage, services, combined);
    }

    /** Replaces the line for one service, keeping its position, since stages revisit the same services. */
    public UpdateReport with(final ServiceLine line) {
        final List<ServiceLine> combined = new ArrayList<>(services.size() + 1);
        boolean replaced = false;
        for (final ServiceLine existing : services) {
            if (existing.service().equals(line.service())) {
                combined.add(line);
                replaced = true;
            } else {
                combined.add(existing);
            }
        }
        if (!replaced) {
            combined.add(line);
        }
        return new UpdateReport(stage, combined, notes);
    }

    /** Returns the same report without the lines for those services, which a run is deliberately leaving alone. */
    public UpdateReport withoutLines(final List<String> gone) {
        if (gone.isEmpty()) {
            return this;
        }
        return new UpdateReport(
                stage,
                services.stream().filter(line -> !gone.contains(line.service())).toList(),
                notes);
    }

    /** Returns the line for that service, or a fresh {@link State#UNCHANGED} one. */
    public ServiceLine line(final String service) {
        return services.stream()
                .filter(existing -> existing.service().equals(service))
                .findFirst()
                .orElseGet(() -> new ServiceLine(service, State.UNCHANGED, List.of(), null));
    }

    /** Returns whether anything is going to move; an artefact with no build is news, not work. */
    public boolean isWork() {
        return services.stream().anyMatch(ServiceLine::isMoving);
    }

    /**
     * Returns whether at least one line is {@link State#SAVED}, which a run's status alone does not prove.
     *
     * It cannot tell which volume failed; steward settles a run {@code FAILED} when any line is.
     */
    public boolean savedSomething() {
        return services.stream().anyMatch(line -> line.state() == State.SAVED);
    }

    /**
     * One service, and what has happened to it so far.
     *
     * @param detail what a failure or a long step says beside the state, a message of the report section, or none
     */
    public record ServiceLine(
            String service,
            State state,
            List<Change> changes,
            @Nullable MessageRef detail) {

        public ServiceLine {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(state, "state");
            changes = List.copyOf(changes == null ? List.of() : changes);
        }

        public ServiceLine at(final State next) {
            return new ServiceLine(service, next, changes, detail);
        }

        public ServiceLine failed(final @Nullable MessageRef why) {
            return new ServiceLine(service, State.FAILED, changes, why);
        }

        /**
         * Returns the same line with a message beside it, without touching the state.
         *
         * A long step says what it is doing before it does it, without publishing a failure.
         */
        public ServiceLine withDetail(final @Nullable MessageRef what) {
            return new ServiceLine(service, state, changes, what);
        }

        /** Returns the same line with one more change on it, keeping the order they were added in. */
        public ServiceLine with(final Change change) {
            final List<Change> combined = new ArrayList<>(changes);
            combined.add(change);
            return new ServiceLine(service, state, combined, detail);
        }

        /** Returns whether a run would stop this service and put a file into its volume. */
        public boolean isMoving() {
            return changes.stream().anyMatch(change -> change.state() == Change.State.MOVING);
        }
    }

    /**
     * One artefact, and what is happening to it.
     *
     * @param from  the version installed now, or {@code null} for a first install
     * @param to    where it is going, or {@link #NONE} when no version names it
     * @param state whether this row is a file moving or an artefact waiting for a build
     * @param told  what happens to it as a message of the report section, for a change no version names, or none
     */
    public record Change(
            String artefact,
            @Nullable String from,
            String to,
            Change.State state,
            @Nullable MessageRef told) {

        /** The {@link Change#to()} of a change no version names; readers branch on the state and on {@link #told}. */
        public static final String NONE = "-";

        public Change {
            Objects.requireNonNull(artefact, "artefact");
            Objects.requireNonNull(to, "to");
            state = state == null ? Change.State.MOVING : state;
        }

        /** A file being installed or replaced. */
        public Change(final String artefact, final @Nullable String from, final String to) {
            this(artefact, from, to, Change.State.MOVING, null);
        }

        /** A change no version names, such as a container made again; it stops its service all the same. */
        public static Change told(final String artefact, final MessageRef what) {
            return new Change(artefact, null, NONE, Change.State.MOVING, Objects.requireNonNull(what, "what"));
        }

        /** An artefact the network wants that has no build for this Minecraft version yet. */
        public static Change unsupported(final String artefact) {
            return new Change(artefact, null, NONE, Change.State.UNSUPPORTED, null);
        }

        /** What is happening to one artefact. */
        public enum State {
            /** A file is being installed or replaced; {@code from} and {@code to} say which. */
            MOVING,
            /** The source has no build for the network's Minecraft version; never work to stop for. */
            UNSUPPORTED
        }
    }

    /**
     * One thing a run did or found, as a record each surface filters and draws for itself.
     *
     * @param service the service, volume or archive it concerns, as a line names it, or none for the run as a whole
     * @param what    a message of the report section, whose typed values are the record's values
     */
    public record Note(Step step, Outcome outcome, @Nullable String service, MessageRef what) {

        public Note {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(what, "what");
        }

        public static Note done(final Step step, final MessageRef what) {
            return new Note(step, Outcome.DONE, null, what);
        }

        public static Note skipped(final Step step, final MessageRef what) {
            return new Note(step, Outcome.SKIPPED, null, what);
        }

        public static Note warning(final Step step, final MessageRef what) {
            return new Note(step, Outcome.WARNING, null, what);
        }

        public static Note failed(final Step step, final MessageRef what) {
            return new Note(step, Outcome.FAILED, null, what);
        }

        /** Returns the same record about one service. */
        public Note on(final String subject) {
            return new Note(step, outcome, Objects.requireNonNull(subject, "subject"), what);
        }

        /** Returns one record per service, in their order, each saying the same about its own. */
        public List<Note> each(final List<String> subjects) {
            return subjects.stream().map(this::on).toList();
        }
    }

    /** The part of a run a {@link Note} is about, in the order a run reaches them. */
    public enum Step {
        /** The run as a whole: who or what ended it. */
        RUN,
        /** What the request covers, and what it leaves out. */
        SCOPE,
        /** What the sources, the registries and the plugin directories answered. */
        SOURCES,
        /** Which release carries the run out, and whether a one-shot does. */
        RELEASE,
        /** The standbys the players are moved to. */
        STANDBY,
        /** The players on a server that is about to stop. */
        PLAYERS,
        /** Stopping the servers, and holding them down. */
        STOP,
        /** The archives a run writes, keeps, removes and copies off the host. */
        BACKUP,
        /** The database schema brought to the release. */
        MIGRATE,
        /** Files put into place, removed or restored. */
        INSTALL,
        /** What a run removes once everything is back. */
        CLEANUP
    }

    /** How a {@link Note} went. */
    public enum Outcome {
        /** It happened as asked. */
        DONE,
        /** Left out on purpose, or nothing to do. */
        SKIPPED,
        /** Needs a look, but failed nothing. */
        WARNING,
        /** Part of why the run did not do what was asked. */
        FAILED
    }

    /** Where a run has got to, in the order a person watching sees them. */
    public enum Stage {

        /** Asking every source what the newest version is; writes nothing. */
        RESOLVING,
        /** Resolved, nothing done yet; the plan a countdown announces. */
        PLANNED,
        /** The countdown is running and players can see it; still cancellable. */
        COUNTDOWN,
        /** Servers are being stopped, in the order the report lists them. */
        STOPPING,
        /** The volumes are being saved with nothing running on them; only a {@code BACKUP} run. */
        BACKING_UP,
        /** The schema is current and the jars are being swapped, with nothing running on them. */
        INSTALLING,
        /** The servers are being started again. */
        STARTING,
        /** Started, and being watched until each one reports healthy. */
        VERIFYING,
        /** Everything asked for happened and every service came back. */
        DONE,
        /** Nothing needed doing, which is a third answer and not a quiet kind of fine. */
        NOTHING_TO_DO,
        /** Something went wrong; the notes and the failed service lines say what. */
        FAILED,
        /** Stopped by a person before it began. */
        CANCELLED;

        /** Returns whether a run in this stage has stopped moving. */
        public boolean isFinished() {
            return this == DONE || this == NOTHING_TO_DO || this == FAILED || this == CANCELLED;
        }
    }

    /** What a kind of run does while its servers are down, which its notes name. */
    public enum Undertaking {
        /** An update installs jars. */
        INSTALL,
        /** A one-shot renews the long-running steward-agent and nothing else. */
        RENEW_AGENT,
        RESTART,
        BACKUP,
        TAKE_DOWN,
        START,
        RECREATE,
        DEPLOY,
        REMOVE_PLUGIN,
        RESTORE_VOLUME,
        RESTORE_DATABASE
    }

    /** What has happened to one service so far. */
    public enum State {

        /** Nothing about this service changes, so it is never stopped. */
        UNCHANGED,
        /** It has changes and the run has not reached it yet. */
        PLANNED,
        /** Stopped, and its jars are safe to replace. */
        STOPPED,
        /** The new jars are in place and it has not been started yet. */
        INSTALLED,
        /** A volume's snapshot is finished; a {@code BACKUP} report carries one line per volume. */
        SAVED,
        /** Started, and not yet reporting healthy. */
        STARTING,
        /** Back, and its own healthcheck says so. */
        HEALTHY,
        /** It did not come back, or its own step failed; the detail says which. */
        FAILED;
    }
}
