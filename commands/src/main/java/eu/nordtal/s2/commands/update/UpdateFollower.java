package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Follows one {@code update_request} row and decides what to tell the person who asked.
 *
 * <h2>Why the deciding is here and the timer is not</h2>
 * Three processes show the answer in chat - the proxy for everybody playing, the Paper servers for
 * their consoles - and each has its own scheduler and its own way of sending a line. What they must
 * not have is their own opinion about when a request is finished, what "gone" means, or how much of
 * a report fits. Until 2026-09-08 that opinion lived in {@code :paper-common}, which the proxy
 * cannot use, and the proxy's answer to that was to show nothing at all.
 *
 * <p>So this class reads the row and answers with {@link Step}: the lines to say, and whether to
 * stop asking. A surface calls {@link #poll} on whatever timer it has and hands each line to its
 * user. Nothing here names a scheduler, a thread or a platform.</p>
 *
 * <h2>The report is drawn from keys, and that is new on 2026-09-08</h2>
 * It used to be printed as {@link UpdateReport#render()}, verbatim, on the rule that nothing is
 * rendered twice. What that produced was an English wall of text for a German admin -
 * {@code "smp: waiting - paper 26.2.121 -> 26.2.126"} - on the one command whose answer is longest.
 * The rule the repository actually holds is that nothing is <em>decided</em> twice, and none of the
 * deciding moved: every version, every state and every outcome below is read straight off the
 * updater's report. What is chosen here is which message key names it.
 *
 * <p>The values that go into those keys - version strings, filenames, an Arcane failure - are
 * substituted as placeholders and are therefore escaped by {@code MessageRenderer}. That is what
 * makes it safe to stop printing them as literals: a version containing {@code <} arrives as text
 * rather than as a MiniMessage tag, which is the property the old rule was protecting.</p>
 *
 * <h2>What still goes out as a literal</h2>
 * A {@code result} written before V12 is plain text and nothing can be said about its structure, so
 * it is printed as it is. A cancellation's {@code result} is the reason somebody typed, which is
 * text too and is wrapped in {@code update.stopped-by} rather than translated.
 */
public final class UpdateFollower {

    /**
     * How long to wait for the updater before giving up on it.
     *
     * <p>An update stops servers, swaps jars and waits up to five minutes for each to report
     * healthy - minutes, not seconds. What this bounds is the case where nothing is listening at
     * all, which looks exactly the same from here until it is said out loud.</p>
     */
    public static final Duration PATIENCE = Duration.ofMinutes(12);

    /** How much of the report goes into chat before it is cut short. */
    public static final int MAX_LINES = 40;

    /** One thing to say: a message key with placeholders, or a literal line of the report. */
    public record Say(String key, Map<String, ?> placeholders, String literal, Tone tone) {

        public static Say key(final String key, final Map<String, ?> placeholders, final Tone tone) {
            return new Say(Objects.requireNonNull(key, "key"), Map.copyOf(placeholders), null,
                    tone == null ? Tone.NEUTRAL : tone);
        }

        public static Say key(final String key, final Map<String, ?> placeholders) {
            return key(key, placeholders, Tone.NEUTRAL);
        }

        public static Say key(final String key) {
            return key(key, Map.of(), Tone.NEUTRAL);
        }

        public static Say key(final String key, final Tone tone) {
            return key(key, Map.of(), tone);
        }

        public static Say literal(final String text) {
            return new Say(null, Map.of(), Objects.requireNonNull(text, "text"), Tone.NEUTRAL);
        }

        /** Sends this line the way the user's surface sends lines. */
        public void to(final NordtalUser user) {
            if (literal != null) {
                user.replyLiteral(literal);
            } else {
                user.reply(key, placeholders, tone);
            }
        }
    }

    /**
     * What one poll decided.
     *
     * @param says     the lines to send, in order; often none
     * @param finished whether the surface should stop polling - the row is settled, gone, unread
     *                 or overdue
     * @param failure  the exception reading the row threw, for the surface's own log; {@code null}
     *                 otherwise
     */
    public record Step(List<Say> says, boolean finished, RuntimeException failure) {

        public Step {
            says = List.copyOf(says);
        }

        /** Lines to say, and the row is not settled - which is every pass while a run works. */
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

    /**
     * The last stage this follower announced, so a stage is spoken once.
     *
     * <p>A run rewrites its report every few seconds and this polls every two, so without it a
     * player would be told "Stopping the servers" a dozen times. Only the transitions are
     * interesting, which is also the whole of what chat can usefully show while a run works -
     * Discord redraws a field per service instead, because it can edit one message.</p>
     */
    private UpdateReport.Stage lastStage;

    /**
     * @param id       the request to follow
     * @param reader   how to read a row back - {@code UpdateDirectory#find}, or a fake
     * @param deadline when to stop waiting for an answer that is not coming
     */
    public UpdateFollower(final long id, final LongFunction<Optional<UpdateRequest>> reader,
                          final Instant deadline) {
        this.id = id;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /** A follower that gives up {@link #PATIENCE} after {@code now}. */
    public static UpdateFollower of(final long id,
                                    final LongFunction<Optional<UpdateRequest>> reader,
                                    final Instant now) {
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
            return new Step(List.of(Say.key("update.failed", Tone.BAD)), true, failure);
        }
        if (row.isEmpty()) {
            return Step.done(List.of(Say.key("update.gone", Tone.WARN)));
        }
        final UpdateRequest request = row.get();
        if (request.status().isFinished()) {
            return Step.done(report(request));
        }
        if (now.isAfter(deadline)) {
            // Names the state the row is in, because PENDING here means one specific thing:
            // nothing is listening, and the updater container is not running.
            return Step.done(List.of(Say.key("update.timeout",
                    Map.of("status", request.status()), Tone.BAD)));
        }
        return Step.saying(stageChange(request));
    }

    /**
     * One line when the run moves to a stage this follower has not announced yet.
     *
     * <p>Silent for a row with no parsable report - a {@code PENDING} row waiting out its countdown
     * has written nothing at all, and the proxy is already counting that down to everybody.</p>
     */
    private List<Say> stageChange(final UpdateRequest request) {
        final Optional<UpdateReport> report = UpdateReports.parse(request.result());
        if (report.isEmpty() || report.get().stage() == lastStage) {
            return List.of();
        }
        lastStage = report.get().stage();
        return List.of(headline(lastStage));
    }

    /**
     * The updater's answer, as lines.
     *
     * <p>Since 2026-09-07 the row carries an {@link UpdateReport} as JSON. A row written before
     * that is plain text and is printed as it is, which is why the fallback exists - and a
     * cancellation is plain text by design, because its {@code result} is the reason somebody
     * typed.</p>
     */
    private static List<Say> report(final UpdateRequest request) {
        final Optional<UpdateReport> parsed = UpdateReports.parse(request.result());
        final List<Say> says = parsed.isPresent()
                ? structured(parsed.get())
                : plain(request);

        if (says.size() <= MAX_LINES) {
            return says;
        }
        final List<Say> cut = new ArrayList<>(says.subList(0, MAX_LINES));
        cut.add(Say.key("update.truncated", Map.of("lines", says.size() - MAX_LINES), Tone.MUTED));
        return cut;
    }

    /** A report, as a headline and a line per service with its changes under it. */
    private static List<Say> structured(final UpdateReport report) {
        final List<Say> says = new ArrayList<>();
        says.add(headline(report.stage()));

        for (final UpdateReport.ServiceLine line : report.services()) {
            final Tone tone = toneOf(line.state());
            says.add(Say.key("update.line." + line.state(),
                    Map.of("service", line.service()), tone));
            for (final UpdateReport.Change change : line.changes()) {
                says.add(sayChange(change));
            }
            if (line.detail() != null && !line.detail().isBlank()) {
                says.add(Say.key("update.detail", Map.of("detail", line.detail()), tone));
            }
        }
        for (final String note : report.notes()) {
            says.add(Say.key("update.note", Map.of("note", note), toneOf(report.stage())));
        }
        return says;
    }

    /**
     * A {@code result} that is not a report: a cancellation reason, or a row from before V12.
     *
     * <p>CANCELLED is deliberately not given the failure line: a stopped countdown is somebody
     * using the way out, and its {@code result} names who did it.</p>
     */
    private static List<Say> plain(final UpdateRequest request) {
        final String stored = request.result();
        if (request.status() == UpdateStatus.CANCELLED) {
            return List.of(Say.key("update.stopped-by",
                    Map.of("reason", stored == null ? "" : stored), Tone.WARN));
        }
        final List<Say> says = new ArrayList<>();
        if (request.status() == UpdateStatus.FAILED) {
            says.add(Say.key("update.failed", Tone.BAD));
        }
        final String text = stored == null ? "(the updater wrote nothing)" : stored;
        for (final String line : text.split("\n", -1)) {
            says.add(Say.literal(line));
        }
        return says;
    }

    /**
     * One artefact, as a key.
     *
     * <p>Which of the three it is comes off {@link UpdateReport.Change#state()} and off whether
     * anything was installed before - both decided by the updater. Every one is {@code MUTED}: a
     * version number under a service line is detail, including the one saying an artefact is still
     * waiting for a build. Nothing here has gone wrong.</p>
     */
    private static Say sayChange(final UpdateReport.Change change) {
        return switch (change.state()) {
            case UNSUPPORTED -> Say.key("update.change.unsupported",
                    Map.of("artefact", change.artefact()), Tone.MUTED);
            case MOVING -> change.from() == null
                    ? Say.key("update.change.new", Map.of(
                            "artefact", change.artefact(), "to", change.to()), Tone.MUTED)
                    : Say.key("update.change", Map.of("artefact", change.artefact(),
                            "from", change.from(), "to", change.to()), Tone.MUTED);
        };
    }

    private static Say headline(final UpdateReport.Stage stage) {
        return Say.key("update.stage." + stage, toneOf(stage));
    }

    /**
     * What a stage is, as news.
     *
     * <p>Only the four terminal stages carry one: everything else is a run in progress, which is
     * neither good nor bad news, it is just where it has got to.</p>
     */
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
            // A finished snapshot is the good news a BACKUP run exists to deliver, the same way a
            // service coming back is an update's.
            case SAVED -> Tone.GOOD;
            case FAILED -> Tone.BAD;
            // Not news: a service with nothing to move is never stopped and never started, and it
            // is listed only so that "the report says nothing about limbo" is not a possible read.
            case UNCHANGED -> Tone.MUTED;
            default -> Tone.NEUTRAL;
        };
    }
}
