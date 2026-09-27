package eu.nordtal.s2.commands.remote;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Tone;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The near end of a travelling command: writes the request, waits for the answer and says it.
 *
 * Nothing blocks: {@link #send} reschedules itself to read the outcome rather than holding a thread.
 */
public final class Outbox {

    /** How long the asker waits, about a Discord interaction's practical patience. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * How often the outcome is read while waiting; one interaction waiting seconds fits a poll, not a {@code LISTEN}.
     */
    public static final Duration POLL = Duration.ofMillis(500);

    private final CommandRequests requests;
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;
    private final Duration poll;
    private final BiConsumer<String, Throwable> warn;

    public Outbox(
            final CommandRequests requests,
            final ScheduledExecutorService scheduler,
            final BiConsumer<String, Throwable> warn) {
        this(requests, scheduler, TIMEOUT, POLL, warn);
    }

    /** Takes the timings, so a test can run the whole wait in milliseconds. */
    Outbox(
            final CommandRequests requests,
            final ScheduledExecutorService scheduler,
            final Duration timeout,
            final Duration poll,
            final BiConsumer<String, Throwable> warn) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.poll = Objects.requireNonNull(poll, "poll");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Sends a command to the process that owns it and answers {@code user} when it comes back.
     *
     * Returns at once; everything after the row is written happens on the scheduler.
     */
    public void send(final Declaration declaration, final NordtalUser user, final Values values) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(values, "values");

        final String arguments;
        try {
            arguments = RequestArguments.encode(declaration, values);
        } catch (final RuntimeException malformed) {
            // The adapter parsed something into a shape the declaration does not describe. Nothing to send.
            warn.accept(declaration.name() + " could not be encoded for sending", malformed);
            user.reply(MESSAGES.command().remote().failed(), Feedback.REFUSED, Tone.BAD);
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
                        user.origin() == NordtalUser.Origin.CONSOLE ? Optional.empty() : user.discordId(),
                        user.origin() == NordtalUser.Origin.CONSOLE ? Optional.empty() : user.minecraftUuid(),
                        Locales.tag(user.locale()),
                        Instant.now().plus(timeout)));
            } catch (final RuntimeException failure) {
                warn.accept("could not send " + declaration.name(), failure);
                user.reply(MESSAGES.command().remote().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            // MUTED: it is the receipt before the wait, not the answer.
            user.reply(
                    MESSAGES.command()
                            .remote()
                            .sent(user.phrase(declaration.target().message())),
                    Tone.MUTED);
            await(id, declaration, user, Instant.now().plus(timeout));
        });
    }

    private void await(final long id, final Declaration declaration, final NordtalUser user, final Instant deadline) {
        // The task reports its own failures to the user; nothing reads the future.
        final ScheduledFuture<?> _ = scheduler.schedule(
                () -> {
                    final Optional<CommandOutcome> outcome;
                    try {
                        outcome = requests.outcome(id);
                    } catch (final RuntimeException failure) {
                        warn.accept("could not read the outcome of " + declaration.name(), failure);
                        user.reply(MESSAGES.command().remote().failed(), Feedback.REFUSED, Tone.BAD);
                        return;
                    }

                    if (outcome.isEmpty()) {
                        // The row is gone. Nothing in this repository deletes one.
                        warn.accept("command request " + id + " vanished while waiting for it", null);
                        user.reply(MESSAGES.command().remote().failed(), Feedback.REFUSED, Tone.BAD);
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

                    final boolean gaveUp;
                    try {
                        gaveUp = requests.expire(id);
                    } catch (final RuntimeException failure) {
                        warn.accept("could not expire " + declaration.name(), failure);
                        user.reply(MESSAGES.command().remote().failed(), Feedback.REFUSED, Tone.BAD);
                        return;
                    }

                    if (gaveUp) {
                        user.reply(
                                MESSAGES.command()
                                        .remote()
                                        .noAnswer(
                                                user.phrase(declaration.target().message())),
                                Feedback.REFUSED,
                                Tone.BAD);
                    } else {
                        // Lost the race, which is the good outcome: it was claimed just as the deadline passed.
                        user.reply(MESSAGES.command().remote().stillRunning(), Feedback.SMALL_SUCCESS, Tone.WARN);
                    }
                },
                poll.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private static void deliver(final CommandOutcome outcome, final NordtalUser user) {
        outcome.result().ifPresent(user::replyLiteral);
        if (outcome.status() == CommandOutcome.Status.FAILED) {
            user.reply(MESSAGES.command().remote().failed(), Feedback.REFUSED, Tone.BAD);
        }
    }
}
