package eu.nordtal.s2.database.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an update run has done so far, as data each surface draws for itself.
 *
 * @param stage    where the run has got to
 * @param services one line per service the run touches, in reading order
 * @param notes    anything not attached to a service
 */
public record UpdateReport(Stage stage, List<ServiceLine> services, List<String> notes) {

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

    public UpdateReport withNote(final String note) {
        final List<String> combined = new ArrayList<>(notes);
        combined.add(note);
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

    /** Returns the whole report as plain text, the only text rendering of an update there is. */
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
    public record ServiceLine(
            String service,
            State state,
            List<Change> changes,
            @Nullable String detail) {

        public ServiceLine {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(state, "state");
            changes = List.copyOf(changes == null ? List.of() : changes);
        }

        public ServiceLine at(final State next) {
            return new ServiceLine(service, next, changes, detail);
        }

        public ServiceLine failed(final @Nullable String why) {
            return new ServiceLine(service, State.FAILED, changes, why);
        }

        /**
         * Returns the same line with a sentence beside it, without touching the state.
         *
         * A long step says what it is doing before it does it, without publishing a failure.
         */
        public ServiceLine withDetail(final @Nullable String what) {
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

        /** Renders as {@code smp: healthy - paper 26.2.121 -> 26.2.126, smp 0.6.0 -> 0.7.0}. */
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
     * @param from  the version installed now, or {@code null} for a first install
     * @param to    where it is going, or {@link #UNSUPPORTED}
     * @param state whether this row is a file moving or an artefact waiting for a build
     */
    public record Change(String artefact, @Nullable String from, String to, Change.State state) {

        /** The {@link Change#to()} of an artefact with no build to move to; readers branch on the state first. */
        public static final String UNSUPPORTED = "-";

        public Change {
            Objects.requireNonNull(artefact, "artefact");
            Objects.requireNonNull(to, "to");
            state = state == null ? Change.State.MOVING : state;
        }

        /** A file being installed or replaced. */
        public Change(final String artefact, final @Nullable String from, final String to) {
            this(artefact, from, to, Change.State.MOVING);
        }

        /** An artefact the network wants that has no build for this Minecraft version yet. */
        public static Change unsupported(final String artefact) {
            return new Change(artefact, null, UNSUPPORTED, Change.State.UNSUPPORTED);
        }

        public String render() {
            return switch (state) {
                case UNSUPPORTED -> artefact + " (no build for this Minecraft version yet)";
                case MOVING -> from == null ? artefact + " " + to : artefact + " " + from + " -> " + to;
            };
        }

        /** What is happening to one artefact. */
        public enum State {
            /** A file is being installed or replaced; {@code from} and {@code to} say which. */
            MOVING,
            /** The source has no build for the network's Minecraft version; never work to stop for. */
            UNSUPPORTED
        }
    }

    /** Where a run has got to, in the order a person watching sees them. */
    public enum Stage {

        /** Asking every source what the newest version is; writes nothing. */
        RESOLVING("Working out what is new..."),
        /** Resolved, nothing done yet; the plan a countdown announces. */
        PLANNED("What is new"),
        /** The countdown is running and players can see it; still cancellable. */
        COUNTDOWN("Updating shortly"),
        /** Servers are being stopped, in the order the report lists them. */
        STOPPING("Stopping the servers"),
        /** The volumes are being saved with nothing running on them; only a {@code BACKUP} run. */
        BACKING_UP("Saving the volumes"),
        /** The schema is current and the jars are being swapped, with nothing running on them. */
        INSTALLING("Installing"),
        /** The servers are being started again. */
        STARTING("Starting the servers"),
        /** Started, and being watched until each one reports healthy. */
        VERIFYING("Waiting for the servers to come back"),
        /** Everything asked for happened and every service came back. */
        DONE("Update finished"),
        /** Nothing needed doing, which is a third answer and not a quiet kind of fine. */
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

        /** Returns whether a run in this stage has stopped moving. */
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
        /** A volume's snapshot is finished; a {@code BACKUP} report carries one line per volume. */
        SAVED("saved"),
        /** Started, and not yet reporting healthy. */
        STARTING("starting"),
        /** Back, and its own healthcheck says so. */
        HEALTHY("running"),
        /** It did not come back, or its own step failed; the detail says which. */
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
