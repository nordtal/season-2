package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an update run has done so far, as data that each surface draws for itself.
 *
 * @param services one line per service the run touches
 * @param notes what the run did or found beside the lines, in order
 */
public record UpdateReport(Stage stage, List<ServiceLine> services, List<Note> notes) {

    public UpdateReport {
        Objects.requireNonNull(stage, "stage");
        services = List.copyOf(services == null ? List.of() : services);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /** Returns an empty report at a stage. */
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

    /** Replaces the line of a service in place. */
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

    /** Returns the report without the lines of those services. */
    public UpdateReport withoutLines(final List<String> gone) {
        if (gone.isEmpty()) {
            return this;
        }
        return new UpdateReport(
                stage,
                services.stream().filter(line -> !gone.contains(line.service())).toList(),
                notes);
    }

    /** Returns the line of the service, or an {@link State#UNCHANGED} one. */
    public ServiceLine line(final String service) {
        return services.stream()
                .filter(existing -> existing.service().equals(service))
                .findFirst()
                .orElseGet(() -> new ServiceLine(service, State.UNCHANGED, List.of(), null));
    }

    /** Returns whether anything is going to move. */
    public boolean isWork() {
        return services.stream().anyMatch(ServiceLine::isMoving);
    }

    /** Returns whether at least one line is {@link State#SAVED}. */
    public boolean savedSomething() {
        return services.stream().anyMatch(line -> line.state() == State.SAVED);
    }

    /** Returns each failed line, then each failed note, in order. A run that did not fail has none. */
    public List<Failure> failures() {
        if (stage != Stage.FAILED && stage != Stage.CANCELLED) {
            return List.of();
        }
        final List<Failure> said = new ArrayList<>();
        for (final ServiceLine line : services) {
            final MessageRef what = line.said();
            if (line.state() == State.FAILED && what != null) {
                said.add(new Failure(line.service(), what));
            }
        }
        for (final Note note : notes) {
            if (note.outcome() == Outcome.FAILED) {
                said.add(new Failure(note.service(), note.what()));
            }
        }
        return List.copyOf(said);
    }

    /** Returns the message of each line by service. */
    public Map<String, MessageRef> said() {
        final Map<String, MessageRef> said = new LinkedHashMap<>();
        for (final ServiceLine line : services) {
            final MessageRef what = line.said();
            if (what != null) {
                said.put(line.service(), what);
            }
        }
        return said;
    }

    /** One reason a run failed, about a service or, without one, about the run. */
    public record Failure(@Nullable String service, MessageRef what) {

        public Failure {
            Objects.requireNonNull(what, "what");
        }
    }

    /**
     * One service and what has happened to it.
     *
     * @param facts what its step came to, or none
     */
    public record ServiceLine(
            String service,
            State state,
            List<Change> changes,
            @Nullable Facts facts) {

        public ServiceLine {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(state, "state");
            changes = List.copyOf(changes == null ? List.of() : changes);
        }

        public ServiceLine at(final State next) {
            return new ServiceLine(service, next, changes, facts);
        }

        public ServiceLine failed(final Facts why) {
            return new ServiceLine(service, State.FAILED, changes, Objects.requireNonNull(why, "why"));
        }

        /** Returns the line with these facts and the same state. */
        public ServiceLine with(final Facts what) {
            return new ServiceLine(service, state, changes, Objects.requireNonNull(what, "what"));
        }

        /** Returns the line with one more change. */
        public ServiceLine with(final Change change) {
            final List<Change> combined = new ArrayList<>(changes);
            combined.add(change);
            return new ServiceLine(service, state, combined, facts);
        }

        /** Returns whether a run would stop this service and put a file into its volume. */
        public boolean isMoving() {
            return changes.stream().anyMatch(change -> change.state() == Change.State.MOVING);
        }

        /** Returns the short message beside the state, or none. */
        public @Nullable MessageRef said() {
            return facts == null ? null : facts.said(state, service);
        }
    }

    /**
     * What the step of a service line came to. {@link ServiceLine#said} draws the short message from it.
     *
     * @param act the step the line is in, or the one that failed
     * @param image the image a recreated container is made from
     * @param reason why the step was refused
     * @param artefact the artefact that did not go in
     * @param fallback what a service is left running on after a refused recreate
     * @param fallbackReason why starting the old container was refused as well
     * @param back whether the service came back
     * @param waited the minutes waited for it
     * @param seen what the runtime last showed of it
     * @param archive the archive it was saved into
     * @param mark whether the mark of an unverified stop lies beside that archive
     */
    public record Facts(
            @Nullable Act act,
            @Nullable Image image,
            @Nullable String reason,
            @Nullable String artefact,
            @Nullable Fallback fallback,
            @Nullable String fallbackReason,
            @Nullable Back back,
            @Nullable Long waited,
            @Nullable Seen seen,
            @Nullable String archive,
            @Nullable Mark mark) {

        /** A step under way. */
        public static Facts of(final Act act) {
            return new Facts(act, null, null, null, null, null, null, null, null, null, null);
        }

        /** A refused step, or none when nothing was asked. */
        public static Facts refused(final Act act, final @Nullable String reason) {
            return new Facts(act, null, reason, null, null, null, null, null, null, null, null);
        }

        public static Facts notInstalled(final String artefact, final String reason) {
            return new Facts(Act.INSTALL, null, reason, artefact, null, null, null, null, null, null, null);
        }

        /** A refused recreate and what the service was left on. */
        public static Facts notRecreated(
                final Image image,
                final String reason,
                final Fallback fallback,
                final @Nullable String fallbackReason) {
            return new Facts(Act.RECREATE, image, reason, null, fallback, fallbackReason, null, null, null, null, null);
        }

        /** A wait that ended without the service. */
        public static Facts notBack(final Back back, final @Nullable Long waited, final @Nullable Seen seen) {
            return of(Act.VERIFY).came(back, waited, seen);
        }

        /** A wait that ended early, because the service refused its start or restarted. */
        public static Facts gaveUp(final Back back, final String reason, final @Nullable Seen seen) {
            return new Facts(Act.VERIFY, null, reason, null, null, null, back, null, seen, null, null);
        }

        /** A volume saved into an archive. */
        public static Facts saved(final @Nullable String archive, final @Nullable Mark mark) {
            return new Facts(Act.BACKUP, null, null, null, null, null, null, null, null, archive, mark);
        }

        /** Returns the facts with the image of the made container. */
        public Facts made(final Image from) {
            return new Facts(act, from, reason, artefact, fallback, fallbackReason, back, waited, seen, archive, mark);
        }

        /** Returns the facts with how the wait ended. */
        public Facts came(final Back now, final @Nullable Long after, final @Nullable Seen last) {
            return new Facts(act, image, reason, artefact, fallback, fallbackReason, now, after, last, archive, mark);
        }

        /** Returns the short message for a line in that state, or none. */
        @Nullable
        MessageRef said(final State state, final String service) {
            final boolean failed = state == State.FAILED;
            if (act == null) {
                return reason == null ? null : TEXTS.report().words(reason);
            }
            return switch (act) {
                case STOP -> stopped(failed);
                case START ->
                    !failed
                            ? null
                            : reason == null
                                    ? TEXTS.report().noContainerToStart()
                                    : TEXTS.report().startFailed(reason);
                case BACKUP -> failed ? words() : marked(service);
                case INSTALL ->
                    failed && artefact != null ? TEXTS.report().notInstalled(artefact, words(reason)) : null;
                case RESTORE -> failed ? words() : null;
                case RECREATE -> failed ? fellBack() : state == State.STARTING ? recreating() : null;
                case RENEW ->
                    failed
                            ? TEXTS.report().agentNotRenewed(words(reason))
                            : state == State.STARTING ? TEXTS.report().renewingAgent() : null;
                case VERIFY ->
                    !failed
                            ? null
                            : switch (back == null ? Back.NO : back) {
                                case INTERRUPTED -> TEXTS.report().waitInterrupted();
                                case REFUSED -> TEXTS.report().startRefused(words(reason));
                                case RESTARTED -> TEXTS.report().restarted(words(reason));
                                case YES, NO -> TEXTS.report().notHealthy(minutes(), sight());
                            };
            };
        }

        private @Nullable MessageRef stopped(final boolean failed) {
            if (!failed) {
                return reason == null ? null : TEXTS.report().words(reason);
            }
            return reason == null
                    ? TEXTS.report().noContainerToStop()
                    : TEXTS.report().stopFailed(reason);
        }

        private @Nullable MessageRef marked(final String service) {
            if (mark == null) {
                return null;
            }
            final String where = archive == null ? service : archive;
            return mark == Mark.WRITTEN
                    ? TEXTS.report().unverifiedArchive(where)
                    : TEXTS.report().unmarkedArchive(where);
        }

        private MessageRef recreating() {
            return TEXTS.report().recreating(image != Image.KEPT);
        }

        private MessageRef fellBack() {
            final boolean outdated = image == Image.OUTDATED;
            final String why = words(reason);
            if (fallback == null) {
                return TEXTS.report().notRecreated(outdated, why);
            }
            return switch (fallback) {
                case NONE -> TEXTS.report().notRecreated(outdated, why);
                case RUNNING -> TEXTS.report().foreignNotRecreated(why);
                case REFUSED -> TEXTS.report().notFellBack(outdated, why, words(fallbackReason));
                case STARTED ->
                    back == null
                            ? TEXTS.report().fellBack(outdated, why)
                            : switch (back) {
                                case YES -> TEXTS.report().fellBackHealthy(outdated, why);
                                case NO, REFUSED, RESTARTED ->
                                    TEXTS.report().fellBackDown(outdated, why, minutes(), sight());
                                case INTERRUPTED -> TEXTS.report().fellBackInterrupted(outdated, why);
                            };
            };
        }

        private MessageRef words() {
            return TEXTS.report().words(words(reason));
        }

        private static String words(final @Nullable String said) {
            return said == null ? "" : said;
        }

        private long minutes() {
            return waited == null ? 0 : waited;
        }

        private MessageRef sight() {
            return seen == null ? TEXTS.report().noContainer() : seen.said();
        }

        /**
         * What the runtime showed of a service.
         *
         * @param status the container status, or none without a container
         * @param health the health state, or none without a healthcheck
         * @param unread the answer of the runtime when it could not be read
         */
        public record Seen(
                @Nullable String status,
                @Nullable String health,
                @Nullable String unread) {

            /** Returns what was seen as a message. */
            public MessageRef said() {
                if (unread != null) {
                    return TEXTS.report().runtimeUnread(unread);
                }
                if (status == null) {
                    return TEXTS.report().noContainer();
                }
                return TEXTS.report().words(health == null || health.isBlank() ? status : status + ", " + health);
            }
        }
    }

    /** The step of a {@link Facts}. */
    public enum Act {
        STOP,
        BACKUP,
        INSTALL,
        RESTORE,
        RECREATE,
        RENEW,
        START,
        VERIFY
    }

    /** The image a container is made from. */
    public enum Image {
        OUTDATED,
        PULLED,
        KEPT
    }

    /** What a service runs on after a refused recreate. */
    public enum Fallback {
        NONE,
        RUNNING,
        STARTED,
        REFUSED
    }

    /** How the wait for a service ended. */
    public enum Back {
        YES,
        NO,
        INTERRUPTED,
        /** A plugin refused the start, which {@code reason} gives. */
        REFUSED,
        /** It restarted while it was waited for; {@code reason} is the last line its log gave why. */
        RESTARTED
    }

    /** Whether the mark of an unverified stop lies beside an archive. */
    public enum Mark {
        WRITTEN,
        NOT_WRITTEN
    }

    /**
     * One artefact and what is happening to it.
     *
     * @param from the installed version, or {@code null} for a first install
     * @param to the new version, or {@link #NONE}
     * @param state whether a file moves or the artefact waits for a build
     * @param told what happens to it, for a change no version names
     */
    public record Change(
            String artefact,
            @Nullable String from,
            String to,
            Change.State state,
            @Nullable MessageRef told) {

        /** The {@link Change#to()} of a change no version names. */
        public static final String NONE = "-";

        public Change {
            Objects.requireNonNull(artefact, "artefact");
            Objects.requireNonNull(to, "to");
            state = state == null ? Change.State.MOVING : state;
        }

        /** A file installed or replaced. */
        public Change(final String artefact, final @Nullable String from, final String to) {
            this(artefact, from, to, Change.State.MOVING, null);
        }

        /** A change no version names. It stops its service all the same. */
        public static Change told(final String artefact, final MessageRef what) {
            return new Change(artefact, null, NONE, Change.State.MOVING, Objects.requireNonNull(what, "what"));
        }

        /** An artefact without a build for this Minecraft version. */
        public static Change unsupported(final String artefact) {
            return new Change(artefact, null, NONE, Change.State.UNSUPPORTED, null);
        }

        /** What happens to an artefact. */
        public enum State {
            MOVING,
            UNSUPPORTED
        }
    }

    /**
     * One thing a run did or found.
     *
     * @param service the service, volume or archive it concerns, or none for the run
     * @param what a message of the report section
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

        /** Returns the note about a service. */
        public Note on(final String subject) {
            return new Note(step, outcome, Objects.requireNonNull(subject, "subject"), what);
        }

        /** Returns one note per service. */
        public List<Note> each(final List<String> subjects) {
            return subjects.stream().map(this::on).toList();
        }
    }

    /** The part of a run a {@link Note} is about, in the order of the run. */
    public enum Step {
        RUN,
        SCOPE,
        SOURCES,
        RELEASE,
        STANDBY,
        PLAYERS,
        STOP,
        BACKUP,
        MIGRATE,
        INSTALL,
        CLEANUP
    }

    /** How a {@link Note} went. */
    public enum Outcome {
        DONE,
        SKIPPED,
        WARNING,
        FAILED
    }

    /** Where a run has got to, in the order of the run. */
    public enum Stage {

        /** Asking every source for the newest version. */
        RESOLVING,
        /** Resolved and not started. */
        PLANNED,
        /** The countdown runs and can be cancelled. */
        COUNTDOWN,
        STOPPING,
        /** Saving the volumes, only in a {@code BACKUP} run. */
        BACKING_UP,
        /** Swapping the jars with nothing running on them. */
        INSTALLING,
        STARTING,
        /** Waiting until each service is healthy. */
        VERIFYING,
        DONE,
        /** Nothing needed doing. */
        NOTHING_TO_DO,
        /** Something went wrong. The notes and the failed lines say what. */
        FAILED,
        CANCELLED;

        /** Returns whether a run in this stage has stopped. */
        public boolean isFinished() {
            return this == DONE || this == NOTHING_TO_DO || this == FAILED || this == CANCELLED;
        }
    }

    /** What a kind of run does while its servers are down. */
    public enum Undertaking {
        INSTALL,
        /** Renews steward-agent and nothing else. */
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

    /** What has happened to a service. */
    public enum State {

        /** Nothing about it changes, so it is not stopped. */
        UNCHANGED,
        /** It has changes and the run has not reached it. */
        PLANNED,
        STOPPED,
        /** The new jars are in place. */
        INSTALLED,
        /** The snapshot of a volume is finished. */
        SAVED,
        /** An archive is back in its volume or database. */
        RESTORED,
        STARTING,
        /** Healthy by its own healthcheck. */
        HEALTHY,
        /** It did not come back, or its step failed. */
        FAILED;
    }
}
