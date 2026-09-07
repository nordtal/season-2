package eu.nordtal.s2.common.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an update run has done so far, as data rather than as a paragraph.
 *
 * <h2>Why this exists at all, and what rule it replaced</h2>
 * Until 2026-09-07 the updater wrote one finished <em>text</em> into {@code update_request.result}
 * and every surface printed it verbatim, under a rule stated in {@code CLAUDE.md} and
 * {@code docs/updater.md}: <em>nothing is rendered twice; a second rendering is the thing that
 * eventually disagrees with the first.</em> That rule was right about the danger and wrong about
 * where to stand: what must not happen twice is the <b>deciding</b>, not the drawing. A code block
 * in Discord is unreadable, and a run that now takes minutes has nothing useful to show while it
 * is happening.
 *
 * <p>So the rule became <b>nothing is decided twice</b>. The updater is still the only thing that
 * resolves versions, compares volumes and knows what happened; it now says so in a shape that
 * Discord can draw as one field per service, a chat window can print as lines, and a console can
 * print as {@link #render()} - which is the same text the old rule produced, generated from this
 * object rather than typed alongside it.</p>
 *
 * <h2>It is written more than once</h2>
 * {@code V7__update_request.sql} said a request is never amended and that {@code result} is the
 * whole of it. That is no longer true and {@code V12} says so: the row now carries the report as
 * JSON and is rewritten as the run moves through its stages, because five minutes of silence
 * looks exactly like a run that has hung.
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
     * Replaces the line for one service, keeping the order the report was built in.
     *
     * <p>Replace rather than append, because the stages walk the same services repeatedly: a
     * service is planned, then stopped, then installed, then healthy, and each of those is the same
     * row of the same table changing its value. Appending would give Discord four fields for one
     * server.</p>
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

    /** @return whether anything at all is going to move - the difference between work and news */
    public boolean isWork() {
        return services.stream().anyMatch(line -> !line.changes().isEmpty());
    }

    /**
     * The whole report as plain text, for a console, a chat window and any surface with no fields.
     *
     * <p>This is the <em>only</em> text rendering in the network. Discord draws the same object as
     * fields instead; nothing anywhere composes a sentence of its own about an update.</p>
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
     * One artefact moving.
     *
     * @param from the version installed now, or {@code null} when nothing is installed - which is a
     *             first deployment and reads as "install" rather than "upgrade"
     */
    public record Change(String artefact, String from, String to) {

        public Change {
            Objects.requireNonNull(artefact, "artefact");
            Objects.requireNonNull(to, "to");
        }

        public String render() {
            return from == null ? artefact + " " + to : artefact + " " + from + " -> " + to;
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
        /** The schema is current and the jars are being swapped, with nothing running on them. */
        INSTALLING("Installing"),
        /** The servers are being started again. */
        STARTING("Starting the servers"),
        /** Started, and being watched until each one reports healthy. */
        VERIFYING("Waiting for the servers to come back"),
        /** Everything asked for happened and every service came back. */
        DONE("Update finished"),
        /** Nothing needed doing - which is a third answer and not a quiet kind of "fine". */
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
