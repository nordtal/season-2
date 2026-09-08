package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.NordtalUser;
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
 * <h2>The report is text and stays text</h2>
 * Report lines go out as literals, never through a message key: the updater's report carries
 * version strings and filenames, and one containing {@code <} would become a MiniMessage tag.
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
    public record Say(String key, Map<String, ?> placeholders, String literal) {

        public static Say key(final String key, final Map<String, ?> placeholders) {
            return new Say(Objects.requireNonNull(key, "key"), Map.copyOf(placeholders), null);
        }

        public static Say key(final String key) {
            return key(key, Map.of());
        }

        public static Say literal(final String text) {
            return new Say(null, Map.of(), Objects.requireNonNull(text, "text"));
        }

        /** Sends this line the way the user's surface sends lines. */
        public void to(final NordtalUser user) {
            if (literal != null) {
                user.replyLiteral(literal);
            } else {
                user.reply(key, placeholders);
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

        static Step keepWaiting() {
            return new Step(List.of(), false, null);
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
            return new Step(List.of(Say.key("update.failed")), true, failure);
        }
        if (row.isEmpty()) {
            return Step.done(List.of(Say.key("update.gone")));
        }
        final UpdateRequest request = row.get();
        if (request.status().isFinished()) {
            return Step.done(report(request));
        }
        if (now.isAfter(deadline)) {
            // Names the state the row is in, because PENDING here means one specific thing:
            // nothing is listening, and the updater container is not running.
            return Step.done(List.of(Say.key("update.timeout",
                    Map.of("id", id, "status", request.status()))));
        }
        return Step.keepWaiting();
    }

    /**
     * The updater's answer, as lines.
     *
     * <p>Since 2026-09-07 the row carries an {@link UpdateReport} as JSON, and its own
     * {@code render()} is the one text form of it - the same one a console prints. A row written
     * before that change is plain text and is printed as it is, which is why the fallback exists.</p>
     */
    private static List<Say> report(final UpdateRequest request) {
        final String stored = request.result();
        final String result = UpdateReports.parse(stored)
                .map(UpdateReport::render)
                .orElseGet(() -> stored == null ? "(the updater wrote nothing)" : stored);
        final String[] lines = result.split("\n", -1);

        final List<Say> says = new ArrayList<>();
        // CANCELLED is deliberately not in here: a stopped countdown is somebody using the way
        // out, and /update cancel has already said so in its own words.
        if (request.status() == UpdateStatus.FAILED) {
            says.add(Say.key("update.failed"));
        }
        for (int line = 0; line < Math.min(lines.length, MAX_LINES); line++) {
            says.add(Say.literal(lines[line]));
        }
        if (lines.length > MAX_LINES) {
            says.add(Say.key("update.truncated", Map.of("lines", lines.length - MAX_LINES)));
        }
        return says;
    }
}
