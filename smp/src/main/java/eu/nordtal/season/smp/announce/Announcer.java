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
 * Sends announcements to the Discord announcement channels as one bot request with a message per language.
 * It runs on the async executor and does not wait for the bot.
 */
public final class Announcer {

    /** The time a request waits in the bot's inbox before it expires. */
    public static final Duration KEEP = Duration.ofHours(1);

    private final Inbox<BotRequest> bot;
    private final List<Locale> locales;
    private final Executor async;
    private final BiConsumer<String, Throwable> warn;

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
     * Sends one announcement with a message per language.
     *
     * @param message returns the message for a language, from the {@code announcement} section of the database bundle
     */
    public void announce(final Function<Locale, MessageRef> message) {
        Objects.requireNonNull(message, "message");
        async.execute(() -> {
            final BotRequest.Announce announcement = announcement(message);
            try {
                bot.submit(announcement, Actor.STEWARD, Schedule.within(KEEP));
            } catch (final RuntimeException failure) {
                warn.accept("could not send the announcement " + announcement.messages(), failure);
            }
        });
    }

    BotRequest.Announce announcement(final Function<Locale, MessageRef> message) {
        final Map<String, MessageRef> messages = new LinkedHashMap<>();
        for (final Locale locale : locales) {
            messages.put(Locales.tag(locale), message.apply(locale));
        }
        return new BotRequest.Announce(messages);
    }
}
