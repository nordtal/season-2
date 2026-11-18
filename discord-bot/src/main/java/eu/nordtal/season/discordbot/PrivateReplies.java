package eu.nordtal.season.discordbot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one private bot message per member: a new one deletes the one before.
 * Discord lets the bot delete a private message only while the token of its last interaction lives.
 */
@Slf4j
public final class PrivateReplies {

    /** How long Discord accepts an interaction token. */
    static final Duration TOKEN = Duration.ofMinutes(15);

    /**
     * The member's current private message.
     *
     * @param message the message id, or {@code null} while it is not known yet
     */
    record Held(InteractionHook hook, @Nullable String message, Instant since) {}

    /** What to do with the member's current private message when another one comes. */
    enum Step {
        KEEP,
        DELETE,
        FORGET
    }

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Set<String> kept = ConcurrentHashMap.newKeySet();
    private final Clock clock;

    public PrivateReplies(final Clock clock) {
        this.clock = clock;
    }

    /** Defers a new private reply, which replaces the member's current private message. */
    public void defer(final IReplyCallback event) {
        final String member = event.getUser().getId();
        event.deferReply(true).queue(hook -> opened(member, hook));
    }

    /** Sends a new private reply, which replaces the member's current private message. */
    public void reply(final IReplyCallback event, final MessageCreateData message) {
        final String member = event.getUser().getId();
        event.reply(message).setEphemeral(true).queue(hook -> opened(member, hook));
    }

    /**
     * Defers an edit of the message whose component the member used.
     * A private message used this way becomes the member's current one.
     */
    public void deferEdit(final ComponentInteraction event) {
        final String member = event.getUser().getId();
        final String message = event.getMessageId();
        final boolean ephemeral = event.getMessage().isEphemeral();
        event.deferEdit().queue(hook -> {
            if (ephemeral) {
                take(member, new Held(hook, message, clock.instant()));
            }
        });
    }

    /** Keeps a message from being deleted while it still waits for an answer, such as a payment link. */
    public void keep(final String message) {
        kept.add(message);
    }

    /** Lets a kept message be deleted again. */
    public void release(final String message) {
        kept.remove(message);
    }

    /** Records a reply sent without a known message id, then learns the id. */
    private void opened(final String member, final InteractionHook hook) {
        hook.retrieveOriginal()
                .queue(
                        message -> take(member, new Held(hook, message.getId(), clock.instant())),
                        failure -> take(member, new Held(hook, null, clock.instant())));
    }

    private void take(final String member, final Held next) {
        final Held before = held.put(member, next);
        if (before != null
                && !kept.contains(String.valueOf(before.message()))
                && step(before, next, clock.instant()) == Step.DELETE) {
            before.hook()
                    .deleteOriginal()
                    .queue(
                            _ -> log.debug("private message replaced: member={}", member),
                            failure -> log.debug(
                                    "private message not deleted: member={} cause={}", member, failure.toString()));
        }
    }

    /** Returns what happens to {@code before} when {@code next} becomes the member's current private message. */
    static Step step(final Held before, final Held next, final Instant now) {
        if (before.message() != null && before.message().equals(next.message())) {
            return Step.KEEP;
        }
        if (before.message() == null || Duration.between(before.since(), now).compareTo(TOKEN) >= 0) {
            return Step.FORGET;
        }
        return Step.DELETE;
    }
}
