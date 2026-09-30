package eu.nordtal.s2.smp.announce;

import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Sends the SMP's lines into the Discord announcement channels: one request in the bot's inbox, one text per language.
 * Fire and forget on the async executor: the request keeps for an hour, so a bot that was down posts when it is back.
 */
public final class Announcer {

    /** The languages a line is rendered in. */
    public static final List<String> LANGUAGES = Languages.NETWORK.tags();

    /** How long a request waits for the bot before it is abandoned: a restart, not an outage. */
    public static final Duration KEEP = Duration.ofHours(1);

    private final Inbox<BotRequest> bot;
    private final Messages messages;
    private final Executor async;
    private final BiConsumer<String, Throwable> warn;

    public Announcer(
            final Inbox<BotRequest> bot,
            final Messages messages,
            final Executor async,
            final BiConsumer<String, Throwable> warn) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.async = Objects.requireNonNull(async, "async");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Renders {@code message} in every language and sends it.
     *
     * @param message a message from this module's spec; the announcement keys carry no glyph, which Discord would draw
     *     as a box
     */
    public void announce(final MessageRef message) {
        Objects.requireNonNull(message, "message");
        announce(locale -> message);
    }

    /**
     * Renders a message whose values depend on the language, such as a milestone's name, and sends it.
     *
     * @param message what to send, asked once per language
     */
    public void announce(final Function<Locale, MessageRef> message) {
        Objects.requireNonNull(message, "message");
        async.execute(() -> {
            final BotRequest.Announce announcement = render(message);
            try {
                // Nobody asked: the network announces its own progress.
                bot.submit(announcement, Actor.STEWARD, Schedule.within(KEEP));
            } catch (final RuntimeException failure) {
                warn.accept("could not send the announcement " + announcement.texts(), failure);
            }
        });
    }

    /** Returns the announcement with one plain text per language, visible for the test. */
    BotRequest.Announce render(final Function<Locale, MessageRef> message) {
        final Map<String, String> texts = new LinkedHashMap<>();
        for (final String tag : LANGUAGES) {
            final Locale locale = Locales.parse(tag);
            texts.put(
                    tag,
                    PlainTextComponentSerializer.plainText()
                            .serialize(MessageRenderer.of(messages).format(locale, message.apply(locale))));
        }
        return new BotRequest.Announce(texts);
    }
}
