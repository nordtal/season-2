package eu.nordtal.s2.commands.update;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.ServiceContext;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Follows one {@code update_request} row and decides what to tell the person who asked.
 *
 * Surfaces call {@link #poll} on their own timers; values are placeholders, so {@code MessageRenderer} escapes them.
 */
public final class UpdateFollower {

    /** How long to wait for steward-worker before giving up on it; a run takes minutes, not seconds. */
    public static final Duration PATIENCE = Duration.ofMinutes(12);

    /** How much of the report goes into chat before it is cut short. */
    public static final int MAX_LINES = 40;

    /** One thing to say: a message key with placeholders, or a literal line of the report. */
    public record Say(
            @Nullable MessageRef message, @Nullable String literal, Tone tone) {

        public static Say key(final MessageRef message, final Tone tone) {
            return new Say(Objects.requireNonNull(message, "message"), null, tone == null ? Tone.NEUTRAL : tone);
        }

        public static Say key(final MessageRef message) {
            return key(message, Tone.NEUTRAL);
        }

        public static Say literal(final String text) {
            return new Say(null, Objects.requireNonNull(text, "text"), Tone.NEUTRAL);
        }

        /** Sends this line the way the user's surface sends lines. */
        public void to(final NordtalUser user) {
            if (literal != null) {
                user.replyLiteral(literal);
            } else {
                user.reply(Objects.requireNonNull(message, "message"), tone);
            }
        }
    }

    /**
     * What one poll decided.
     *
     * @param says     the lines to send, in order; often none
     * @param finished whether the surface should stop polling: the row is settled, gone, unread or overdue
     * @param failure  the exception reading the row threw, for the surface's own log; {@code null} otherwise
     */
    public record Step(
            List<Say> says, boolean finished, @Nullable RuntimeException failure) {

        public Step {
            says = List.copyOf(says);
        }

        /** Lines to say while the row is not settled, which is every pass while a run works. */
        static Step saying(final List<Say> says) {
            return new Step(says, false, null);
        }

        static Step done(final List<Say> says) {
            return new Step(says, true, null);
        }

        /** Hands every line to the user, in order. */
        public void deliver(final NordtalUser user) {
            says.forEach(say -> say.to(user));
        }
    }

    private final long id;
    private final LongFunction<Optional<UpdateRequest>> reader;
    private final Instant deadline;

    /** The last stage announced; only touched inside {@code synchronized} {@link #stageChange}, as polls overlap. */
    private UpdateReport.@Nullable Stage lastStage;

    /**
     * @param id       the request to follow
     * @param reader   how to read a row back, {@code UpdateDirectory#find} or a fake
     * @param deadline when to stop waiting for an answer that is not coming
     */
    public UpdateFollower(final long id, final LongFunction<Optional<UpdateRequest>> reader, final Instant deadline) {
        this.id = id;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /** Returns a follower that gives up {@link #PATIENCE} after {@code now}. */
    public static UpdateFollower of(
            final long id, final LongFunction<Optional<UpdateRequest>> reader, final Instant now) {
        return new UpdateFollower(id, reader, now.plus(PATIENCE));
    }

    public long id() {
        return id;
    }

    /**
     * Reads the row once and decides.
     *
     * @param now the surface's clock
     * @return the lines to say and whether to keep polling
     */
    public Step poll(final Instant now) {
        final Optional<UpdateRequest> row;
        try {
            row = reader.apply(id);
        } catch (final RuntimeException failure) {
            return new Step(List.of(Say.key(MESSAGES.update().failed(), Tone.BAD)), true, failure);
        }
        if (row.isEmpty()) {
            return Step.done(List.of(Say.key(MESSAGES.update().gone(), Tone.WARN)));
        }
        final UpdateRequest request = row.get();
        if (request.status().isFinished()) {
            return Step.done(report(request));
        }
        if (now.isAfter(deadline)) {
            // Names the state the row is in, because PENDING here means one specific thing: nothing is listening.
            return Step.done(List.of(Say.key(MESSAGES.update().timeout(request.status()), Tone.BAD)));
        }
        return Step.saying(stageChange(request));
    }

    /** One line when the run reaches a stage not yet announced; silent for a row with no parsable report. */
    private synchronized List<Say> stageChange(final UpdateRequest request) {
        final Optional<UpdateReport> report = UpdateReports.parse(request.result());
        if (report.isEmpty() || report.get().stage() == lastStage) {
            return List.of();
        }
        lastStage = report.get().stage();
        return List.of(headline(lastStage));
    }

    /** steward-worker's answer as lines, falling back to plain text for a result that is not a report. */
    private static List<Say> report(final UpdateRequest request) {
        final Optional<UpdateReport> parsed = UpdateReports.parse(request.result());
        final List<Say> says = parsed.isPresent() ? structured(parsed.get()) : plain(request);

        if (says.size() <= MAX_LINES) {
            return says;
        }
        final List<Say> cut = new ArrayList<>(says.subList(0, MAX_LINES));
        cut.add(Say.key(MESSAGES.update().truncated(says.size() - MAX_LINES), Tone.MUTED));
        return cut;
    }

    /** A report, as a headline and a line per service with its changes under it. */
    private static List<Say> structured(final UpdateReport report) {
        final List<Say> says = new ArrayList<>();
        says.add(headline(report.stage()));

        for (final UpdateReport.ServiceLine line : report.services()) {
            final Tone tone = toneOf(line.state());
            says.add(Say.key(MESSAGES.update().line(line.state(), new ServiceContext(line.service())), tone));
            for (final UpdateReport.Change change : line.changes()) {
                says.add(sayChange(change));
            }
            if (line.detail() != null && !line.detail().isBlank()) {
                says.add(Say.key(MESSAGES.update().detail(line.detail()), tone));
            }
        }
        for (final String note : report.notes()) {
            says.add(Say.key(MESSAGES.update().note(note), toneOf(report.stage())));
        }
        return says;
    }

    /** A {@code result} that is not a report, such as a cancellation reason; CANCELLED gets no failure line. */
    private static List<Say> plain(final UpdateRequest request) {
        final String stored = request.result();
        if (request.status() == UpdateStatus.CANCELLED) {
            return List.of(Say.key(MESSAGES.update().stoppedBy(stored == null ? "" : stored), Tone.WARN));
        }
        final List<Say> says = new ArrayList<>();
        if (request.status() == UpdateStatus.FAILED) {
            says.add(Say.key(MESSAGES.update().failed(), Tone.BAD));
        }
        final String text = stored == null ? "(Steward wrote nothing)" : stored;
        for (final String line : text.split("\n", -1)) {
            says.add(Say.literal(line));
        }
        return says;
    }

    /** One artefact as a key, always {@code MUTED}: a version under a service line is detail. */
    private static Say sayChange(final UpdateReport.Change change) {
        return switch (change.state()) {
            case UNSUPPORTED -> Say.key(MESSAGES.update().changeSection().unsupported(change.artefact()), Tone.MUTED);
            case MOVING ->
                change.from() == null
                        ? Say.key(
                                MESSAGES.update().changeSection().newMessage(change.artefact(), change.to()),
                                Tone.MUTED)
                        : Say.key(MESSAGES.update().change(change.artefact(), change.from(), change.to()), Tone.MUTED);
        };
    }

    private static Say headline(final UpdateReport.Stage stage) {
        return Say.key(MESSAGES.update().stage(stage), toneOf(stage));
    }

    /** What a stage is as news; only the four terminal stages carry a tone. */
    private static Tone toneOf(final UpdateReport.Stage stage) {
        return switch (stage) {
            case DONE -> Tone.GOOD;
            case FAILED -> Tone.BAD;
            case CANCELLED -> Tone.WARN;
            case NOTHING_TO_DO -> Tone.MUTED;
            default -> Tone.NEUTRAL;
        };
    }

    private static Tone toneOf(final UpdateReport.State state) {
        return switch (state) {
            case HEALTHY -> Tone.GOOD;
            // A finished snapshot is the good news a BACKUP run exists to deliver.
            case SAVED -> Tone.GOOD;
            case FAILED -> Tone.BAD;
            // Not news: a service with nothing to move is never stopped and never started.
            case UNCHANGED -> Tone.MUTED;
            default -> Tone.NEUTRAL;
        };
    }
}
