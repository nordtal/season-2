package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Locales;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The near end of a travelling command: write the request, wait for the answer, say it.
 *
 * <h2>Nothing here blocks</h2>
 * The two callers are a Brigadier handler on a server's main thread and a JDA gateway thread with a
 * three second budget. So {@link #send} writes the row on the supplied scheduler and then
 * <em>reschedules itself</em> to look at the outcome, rather than sleeping in a loop: waiting thirty
 * seconds for the SMP to answer must cost a scheduled task, not a held thread.
 *
 * <h2>Giving up is a decision the asker takes alone</h2>
 * When the deadline passes, the asker marks the row {@code EXPIRED} - and that update only touches a
 * row still {@code PENDING}, so a target that claimed it a moment ago keeps it. Losing that race is
 * the good case and it is reported as its own sentence: the command <em>is</em> running, the answer
 * is simply not coming back through this interaction any more. Cancelling work already underway is
 * not on offer and should not be: a half-applied {@code /smp aura} is worse than a slow one.
 *
 * <h2>What "no answer at all" means</h2>
 * A row nothing ever claimed says the process that owns the command is not listening - a backend
 * that is down, or one whose inbox failed to start. That is a different sentence from a target that
 * claimed the request and never settled it, and the two stay distinguishable because the target
 * never writes {@code EXPIRED} itself.
 */
public final class Outbox {

    /** How long the asker waits. Half a minute is a Discord interaction's practical patience. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * How often the outcome is read while waiting.
     *
     * <p>A poll and not a second {@code LISTEN}: the answer is wanted by exactly one waiting
     * interaction, in one process, for a few seconds - which is the shape a poll fits and a
     * notification channel does not. Half a second is under the threshold at which a person watching
     * a spinner notices, and sixty reads of one indexed row over the whole wait is nothing.</p>
     */
    public static final Duration POLL = Duration.ofMillis(500);

    private final CommandRequests requests;
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;
    private final Duration poll;
    private final BiConsumer<String, Throwable> warn;

    public Outbox(final CommandRequests requests, final ScheduledExecutorService scheduler,
                  final BiConsumer<String, Throwable> warn) {
        this(requests, scheduler, TIMEOUT, POLL, warn);
    }

    /** Package-visible timings, so a test can run the whole wait in milliseconds. */
    Outbox(final CommandRequests requests, final ScheduledExecutorService scheduler,
           final Duration timeout, final Duration poll, final BiConsumer<String, Throwable> warn) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.poll = Objects.requireNonNull(poll, "poll");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Send a command to the process that owns it, and answer {@code user} when it comes back.
     *
     * <p>Returns at once. Everything after the row is written happens on the scheduler.</p>
     */
    public void send(final Declaration declaration, final NordtalUser user, final Values values) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(values, "values");

        final String arguments;
        try {
            arguments = RequestArguments.encode(declaration, values);
        } catch (final RuntimeException malformed) {
            // The adapter parsed something into a shape the declaration does not describe. Nothing
            // to send, and nothing a person can do about it - so it is logged in full and answered
            // with the same sentence a failure on the far side gets.
            warn.accept(declaration.name() + " could not be encoded for sending", malformed);
            user.reply("command.remote.failed", Map.of(), Feedback.REFUSED);
            return;
        }

        scheduler.execute(() -> {
            final long id;
            try {
                id = requests.submit(new NewCommandRequest(
                        declaration.target().name(),
                        String.join(" ", declaration.path()),
                        arguments,
                        user.origin().name(),
                        user.name(),
                        user.origin() == NordtalUser.Origin.CONSOLE
                                ? Optional.empty() : user.discordId(),
                        user.origin() == NordtalUser.Origin.CONSOLE
                                ? Optional.empty() : user.minecraftUuid(),
                        Locales.tag(user.locale()),
                        Instant.now().plus(timeout)));
            } catch (final RuntimeException failure) {
                warn.accept("could not send " + declaration.name(), failure);
                user.reply("command.remote.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply("command.remote.sent",
                    Map.of("target", user.phrase(declaration.target().messageKey())));
            await(id, declaration, user, Instant.now().plus(timeout));
        });
    }

    private void await(final long id, final Declaration declaration, final NordtalUser user,
                       final Instant deadline) {
        scheduler.schedule(() -> {
            final Optional<CommandOutcome> outcome;
            try {
                outcome = requests.outcome(id);
            } catch (final RuntimeException failure) {
                warn.accept("could not read the outcome of " + declaration.name(), failure);
                user.reply("command.remote.failed", Map.of(), Feedback.REFUSED);
                return;
            }

            if (outcome.isEmpty()) {
                // The row is gone. Nothing in this repository deletes one, so this is a database
                // somebody has been in by hand - worth a log line and a plain refusal.
                warn.accept("command request " + id + " vanished while waiting for it", null);
                user.reply("command.remote.failed", Map.of(), Feedback.REFUSED);
                return;
            }

            final CommandOutcome answer = outcome.get();
            if (!answer.pending()) {
                deliver(answer, user);
                return;
            }

            if (Instant.now().isBefore(deadline)) {
                await(id, declaration, user, deadline);
                return;
            }

            if (requests.expire(id)) {
                user.reply("command.remote.no-answer",
                        Map.of("target", user.phrase(declaration.target().messageKey())),
                        Feedback.REFUSED);
            } else {
                // Lost the race, which is the good outcome: it was claimed while the deadline
                // passed and is running now. The answer just is not coming back here.
                user.reply("command.remote.still-running", Map.of(), Feedback.SMALL_SUCCESS);
            }
        }, poll.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void deliver(final CommandOutcome outcome, final NordtalUser user) {
        outcome.result().ifPresent(user::replyLiteral);
        if (outcome.status() == CommandOutcome.Status.FAILED) {
            user.reply("command.remote.failed", Map.of(), Feedback.REFUSED);
        }
    }
}
