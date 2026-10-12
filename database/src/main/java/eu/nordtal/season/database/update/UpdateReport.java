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
     * Returns what explains a failed or stopped run: what each failed line says, then each failed record, in order.
     *
     * A run that went well, or is still going, explains nothing; the bot's card and a failed run's alert say these.
     */
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

    /** Returns what each line says beside its state, by service in reading order, for a surface that draws no facts. */
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

    /** One reason a run failed, about one service or, without one, about the run. */
    public record Failure(@Nullable String service, MessageRef what) {

        public Failure {
            Objects.requireNonNull(what, "what");
        }
    }

    /**
     * One service, and what has happened to it so far.
     *
     * @param facts what its step came to, or none while there is nothing to say beside the state
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

        /**
         * Returns the same line with these facts, without touching the state.
         *
         * A long step says what it is doing before it does it, without publishing a failure.
         */
        public ServiceLine with(final Facts what) {
            return new ServiceLine(service, state, changes, Objects.requireNonNull(what, "what"));
        }

        /** Returns the same line with one more change on it, keeping the order they were added in. */
        public ServiceLine with(final Change change) {
            final List<Change> combined = new ArrayList<>(changes);
            combined.add(change);
            return new ServiceLine(service, state, combined, facts);
        }

        /** Returns whether a run would stop this service and put a file into its volume. */
        public boolean isMoving() {
            return changes.stream().anyMatch(change -> change.state() == Change.State.MOVING);
        }

        /** Returns the short message every surface shows beside the state, drawn from the facts, or none. */
        public @Nullable MessageRef said() {
            return facts == null ? null : facts.said(state, service);
        }
    }

    /**
     * What a service line's step came to, as typed values; {@link ServiceLine#said} draws the short message from them.
     *
     * @param act the step the line is in, or the one that failed; none for words that name no step
     * @param image for a recreate, which image the container is made from
     * @param reason why the step was refused, in the words of what refused it
     * @param artefact for an install, the artefact that did not go in
     * @param fallback for a refused recreate, what the service is left running on
     * @param fallbackReason why starting the old container was refused as well
     * @param back whether the service came back, or none while that is not known
     * @param waited the minutes waited for it to come back
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

        /** A step under way, with nothing else known yet. */
        public static Facts of(final Act act) {
            return new Facts(act, null, null, null, null, null, null, null, null, null, null);
        }

        /** A step that was refused, with what refused it, or none when nothing was asked, as without a container. */
        public static Facts refused(final Act act, final @Nullable String reason) {
            return new Facts(act, null, reason, null, null, null, null, null, null, null, null);
        }

        public static Facts notInstalled(final String artefact, final String reason) {
            return new Facts(Act.INSTALL, null, reason, artefact, null, null, null, null, null, null, null);
        }

        /** A recreate that was refused, and what the service was left on. */
        public static Facts notRecreated(
                final Image image,
                final String reason,
                final Fallback fallback,
                final @Nullable String fallbackReason) {
            return new Facts(Act.RECREATE, image, reason, null, fallback, fallbackReason, null, null, null, null, null);
        }

        /** A wait for the service to come back that ended without it. */
        public static Facts notBack(final Back back, final @Nullable Long waited, final @Nullable Seen seen) {
            return of(Act.VERIFY).came(back, waited, seen);
        }

        /** A volume saved into an archive, marked when a stop before it was not verified. */
        public static Facts saved(final @Nullable String archive, final @Nullable Mark mark) {
            return new Facts(Act.BACKUP, null, null, null, null, null, null, null, null, archive, mark);
        }

        /** Returns the same facts about a container made from that image. */
        public Facts made(final Image from) {
            return new Facts(act, from, reason, artefact, fallback, fallbackReason, back, waited, seen, archive, mark);
        }

        /** Returns the same facts with how the wait for the service ended. */
        public Facts came(final Back now, final @Nullable Long after, final @Nullable Seen last) {
            return new Facts(act, image, reason, artefact, fallback, fallbackReason, now, after, last, archive, mark);
        }

        /** The short message for a line in that state, or none when the facts add nothing to the state. */
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
                            : back == Back.INTERRUPTED
                                    ? TEXTS.report().waitInterrupted()
                                    : TEXTS.report().notHealthy(minutes(), sight());
            };
        }

        private @Nullable MessageRef stopped(final boolean failed) {
            if (!failed) {
                // A stop whose ending could not be read back keeps the runtime's answer on a line that did stop.
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
                                case NO -> TEXTS.report().fellBackDown(outdated, why, minutes(), sight());
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
         * What the runtime showed of a service: Docker's status and health, or why it could not be read.
         *
         * @param status Docker's container status, or none when the project has no container for the service
         * @param health Docker's health state, or none for a service without a healthcheck
         * @param unread what the runtime answered when it could not be read at all
         */
        public record Seen(
                @Nullable String status,
                @Nullable String health,
                @Nullable String unread) {

            /** Returns what was seen in the words every surface shows. */
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

    /** The step a {@link Facts} is about. */
    public enum Act {
        STOP,
        /** Saving a volume or the database into an archive. */
        BACKUP,
        /** Putting the new jars into place. */
        INSTALL,
        /** Putting an archive back. */
        RESTORE,
        /** Making a container again. */
        RECREATE,
        /** Making the long-running steward-agent again at a one-shot's release. */
        RENEW,
        START,
        /** Waiting for a started service to report healthy. */
        VERIFY
    }

    /** Which image a container is made again from. */
    public enum Image {
        /** A pulled one, since the registry has a newer image than the one it ran. */
        OUTDATED,
        /** A pulled one, as the kind of run asks. */
        PULLED,
        /** The one it has. */
        KEPT
    }

    /** What a service runs on after its recreate was refused. */
    public enum Fallback {
        /** Nothing: there was no old container to go back to. */
        NONE,
        /** The old container, which was never stopped. */
        RUNNING,
        /** The old container, started again. */
        STARTED,
        /** Nothing: starting the old container was refused as well. */
        REFUSED
    }

    /** How the wait for a service to come back ended. */
    public enum Back {
        YES,
        /** Not within the patience; the facts carry the minutes and what was seen last. */
        NO,
        /** steward-agent stopped while it waited. */
        INTERRUPTED
    }

    /** Whether the mark of an unverified stop lies beside an archive. */
    public enum Mark {
        WRITTEN,
        /** It could not be written, so nothing beside the archive says it. */
        NOT_WRITTEN
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
        /** An archive is back in its volume or its database; a {@code RESTORE} report's line for the archive. */
        RESTORED,
        /** Started, and not yet reporting healthy. */
        STARTING,
        /** Back, and its own healthcheck says so. */
        HEALTHY,
        /** It did not come back, or its own step failed; the facts say which. */
        FAILED;
    }
}
