package eu.nordtal.s2.common.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an update run has done so far, as data rather than as a paragraph.
 *
 * <p><b>Nothing is decided twice.</b> The updater is the only thing that resolves versions, compares
 * volumes and knows what happened; it says so in a shape each surface draws for itself - Discord as
 * one field per service, a console as {@link #render()}.
 *
 * <p>The row carries this as JSON and is rewritten as the run moves through its stages, because
 * minutes of silence look exactly like a run that has hung.
 *
 * @param stage    where the run has got to
 * @param services one line per service the run touches, in the order a person should read them
 * @param notes    anything not attached to a service - the pack, the migration, why nothing
 *                 happened
 */
public record UpdateReport(Stage stage, List<ServiceLine> services, List<String> notes) {

    public UpdateReport {
        Objects.requireNonNull(stage, "stage");
        services = List.copyOf(services == null ? List.of() : services);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /** An empty report at a stage, for the first write of a run. */
    public static UpdateReport at(final Stage stage) {
        return new UpdateReport(stage, List.of(), List.of());
    }

    public UpdateReport withStage(final Stage next) {
        return new UpdateReport(next, services, notes);
    }

    public UpdateReport withNote(final String note) {
        final List<String> combined = new ArrayList<>(notes);
        combined.add(note);
        return new UpdateReport(stage, services, combined);
    }

    /**
     * Replaces the line for one service, keeping the order the report was built in. Replace rather
     * than append, because the stages walk the same services repeatedly and appending would give
     * Discord four fields for one server.
     */
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

    /** @return the line for that service, or a fresh {@link State#UNCHANGED} one */
    public ServiceLine line(final String service) {
        return services.stream()
                .filter(existing -> existing.service().equals(service))
                .findFirst()
                .orElseGet(() -> new ServiceLine(service, State.UNCHANGED, List.of(), null));
    }

    /**
     * @return whether anything at all is going to move - the difference between work and news. An
     *         artefact with no build for this Minecraft version is news, so a run that found nothing
     *         else ends at {@link Stage#NOTHING_TO_DO} and no server is stopped for it
     */
    public boolean isWork() {
        return services.stream().anyMatch(ServiceLine::isMoving);
    }

    /**
     * The whole report as plain text, for a console, a chat window and any surface with no fields.
     * The only text rendering there is - nothing anywhere composes a sentence of its own about an
     * update.
     */
    public String render() {
        final StringBuilder text = new StringBuilder(stage.headline());
        for (final ServiceLine line : services) {
            text.append('\n').append(line.render());
        }
        for (final String note : notes) {
            text.append('\n').append(note);
        }
        return text.toString();
    }

    /** One service, and what has happened to it so far. */
    public record ServiceLine(String service, State state, List<Change> changes, String detail) {

        public ServiceLine {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(state, "state");
            changes = List.copyOf(changes == null ? List.of() : changes);
        }

        public ServiceLine at(final State next) {
            return new ServiceLine(service, next, changes, detail);
        }

        public ServiceLine failed(final String why) {
            return new ServiceLine(service, State.FAILED, changes, why);
        }

        /**
         * The same line with a sentence beside it, without touching the state.
         *
         * <p>Separate from {@link #failed(String)}, which sets both: a step that is going to take
         * minutes and can end this process - pulling an image and recreating the container - has to
         * be able to say what it is doing <em>before</em> it does it, and saying it through
         * {@code failed} would publish a failure that has not happened.</p>
         */
        public ServiceLine withDetail(final String what) {
            return new ServiceLine(service, state, changes, what);
        }

        /** The same line with one more change on it, keeping the order they were added in. */
        public ServiceLine with(final Change change) {
            final List<Change> combined = new ArrayList<>(changes);
            combined.add(change);
            return new ServiceLine(service, state, combined, detail);
        }

        /**
         * @return whether a run would stop this service and put a file into its volume - the one
         *         place that is decided. A line carrying only {@link Change.State#UNSUPPORTED} rows
         *         has something to say and nothing to do
         */
        public boolean isMoving() {
            return changes.stream().anyMatch(change -> change.state() == Change.State.MOVING);
        }

        /** {@code smp: healthy - paper 26.2.121 -> 26.2.126, smp 0.6.0 -> 0.7.0} */
        public String render() {
            final StringBuilder text = new StringBuilder(service).append(": ").append(state.label());
            if (!changes.isEmpty()) {
                text.append(" - ");
                for (int i = 0; i < changes.size(); i++) {
                    if (i > 0) {
                        text.append(", ");
                    }
                    text.append(changes.get(i).render());
                }
            }
            if (detail != null && !detail.isBlank()) {
                text.append(" (").append(detail).append(')');
            }
            return text.toString();
        }
    }

    /**
     * One artefact, and what is happening to it.
     *
     * @param from  the version installed now, or {@code null} when nothing is installed - which is
     *              a first deployment and reads as "install" rather than "upgrade"
     * @param to    where it is going. The literal {@link #UNSUPPORTED} for an artefact that is
     *              going nowhere, so that {@link #render()} and the codec have one shape to handle
     * @param state whether this row is a file moving or an artefact waiting for a build
     */
    public record Change(String artefact, String from, String to, State state) {

        /**
         * What {@link Change#to()} carries for an artefact that has no build to move to. A
         * sentinel rather than {@code null}, because every reader relies on {@code to} being set;
         * nothing renders it, since every surface branches on {@link #state()} first.
         */
        public static final String UNSUPPORTED = "-";

        public Change {
            Objects.requireNonNull(artefact, "artefact");
            Objects.requireNonNull(to, "to");
            state = state == null ? State.MOVING : state;
        }

        /** A file being installed or replaced. */
        public Change(final String artefact, final String from, final String to) {
            this(artefact, from, to, State.MOVING);
        }

        /**
         * An artefact the network wants and its publisher has not built for this Minecraft version.
         * It stays in the report so that it stays named while it waits.
         */
        public static Change unsupported(final String artefact) {
            return new Change(artefact, null, UNSUPPORTED, State.UNSUPPORTED);
        }

        public String render() {
            return switch (state) {
                case UNSUPPORTED -> artefact + " (no build for this Minecraft version yet)";
                case MOVING -> from == null ? artefact + " " + to : artefact + " " + from + " -> " + to;
            };
        }

        /** What is happening to one artefact. */
        public enum State {
            /** A file is being installed or replaced. {@code from} and {@code to} say which. */
            MOVING,
            /**
             * Nothing is happening and nothing has gone wrong: the source answered and has no
             * build of this artefact for the Minecraft version the network runs. A service whose
             * only changes are these is <b>not</b> work and is never stopped.
             */
            UNSUPPORTED
        }
    }

    /** Where a run has got to. Read by a person watching an embed change, so the order matters. */
    public enum Stage {

        /** Asking every source what the newest version is. Writes nothing. */
        RESOLVING("Working out what is new..."),
        /** Resolved, nothing done. This is where a {@code REPORT} ends. */
        PLANNED("What is new"),
        /** The countdown is running and players can see it. Still cancellable. */
        COUNTDOWN("Updating shortly"),
        /** Servers are being stopped, in the order the report lists them. */
        STOPPING("Stopping the servers"),
        /** The volumes are being saved, with nothing running on them. Only a {@code BACKUP} run. */
        BACKING_UP("Saving the volumes"),
        /** The schema is current and the jars are being swapped, with nothing running on them. */
        INSTALLING("Installing"),
        /** The servers are being started again. */
        STARTING("Starting the servers"),
        /** Started, and being watched until each one reports healthy. */
        VERIFYING("Waiting for the servers to come back"),
        /** Everything asked for happened and every service came back. */
        DONE("Update finished"),
        /** Nothing needed doing - a third answer, not a quiet kind of "fine". */
        NOTHING_TO_DO("Everything is already current"),
        /** Something went wrong; the notes and the failed service lines say what. */
        FAILED("The update failed"),
        /** Stopped by a person before it began. */
        CANCELLED("Stopped");

        private final String headline;

        Stage(final String headline) {
            this.headline = headline;
        }

        public String headline() {
            return headline;
        }

        /** @return whether a run in this stage has stopped moving */
        public boolean isFinished() {
            return this == DONE || this == NOTHING_TO_DO || this == FAILED || this == CANCELLED;
        }
    }

    /** What has happened to one service so far. */
    public enum State {

        /** Nothing about this service changes, so it is never stopped. */
        UNCHANGED("unchanged"),
        /** It has changes and the run has not reached it yet. */
        PLANNED("waiting"),
        /** Stopped, and its jars are safe to replace. */
        STOPPED("stopped"),
        /** The new jars are in place and it has not been started yet. */
        INSTALLED("updated"),
        /**
         * A volume's snapshot is finished. Not a service state: a {@code BACKUP} run's report
         * carries one line per volume beside its lines per service.
         */
        SAVED("saved"),
        /** Started, and not yet reporting healthy. */
        STARTING("starting"),
        /** Back, and its own healthcheck says so. */
        HEALTHY("running"),
        /** It did not come back, or something in its own step failed. The detail says which. */
        FAILED("FAILED");

        private final String label;

        State(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
