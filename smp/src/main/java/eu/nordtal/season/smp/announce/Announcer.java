package eu.nordtal.season.smp.announce;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.messages.MessageRef;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Sends the SMP's lines into the Discord announcement channels: one request in the bot's inbox for every language.
 * Each language's message is rendered by the bot. Fire and forget on the async executor: the request keeps an hour.
 */
public final class Announcer {

    /** How long a request waits for the bot before it is abandoned: a restart, not an outage. */
    public static final Duration KEEP = Duration.ofHours(1);

    private final Inbox<BotRequest> bot;
    private final List<Locale> locales;
    private final Executor async;
    private final BiConsumer<String, Throwable> warn;

    /** @param locales the languages the plugin loaded, which are the network's */
    public Announcer(
            final Inbox<BotRequest> bot,
            final List<Locale> locales,
            final Executor async,
            final BiConsumer<String, Throwable> warn) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.locales = List.copyOf(locales);
        this.async = Objects.requireNonNull(async, "async");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Sends a message whose values depend on the language, such as a milestone's name.
     *
     * @param message what to send, asked once per language, from the database bundle's {@code announcement} section
     */
    public void announce(final Function<Locale, MessageRef> message) {
        Objects.requireNonNull(message, "message");
        async.execute(() -> {
            final BotRequest.Announce announcement = announcement(message);
            try {
                // Nobody asked: the network announces its own progress.
                bot.submit(announcement, Actor.STEWARD, Schedule.within(KEEP));
            } catch (final RuntimeException failure) {
                warn.accept("could not send the announcement " + announcement.messages(), failure);
            }
        });
    }

    /** Returns the announcement with one message per language, visible for the test. */
    BotRequest.Announce announcement(final Function<Locale, MessageRef> message) {
        final Map<String, MessageRef> messages = new LinkedHashMap<>();
        for (final Locale locale : locales) {
            messages.put(Locales.tag(locale), message.apply(locale));
        }
        return new BotRequest.Announce(messages);
    }
}
