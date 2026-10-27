package eu.nordtal.season.smp.announce;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Schedule;
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
 * Sends announcements to the Discord announcement channels as one bot request with a line per language.
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
     * Sends the announcement of a milestone with its name in each language. The bot renders the text when it posts.
     *
     * @param message the bot's document that names the milestone
     * @param name returns the milestone's name in a language
     */
    public void announce(
            final BotRequest.Announce.Message message, final String milestone, final Function<Locale, String> name) {
        Objects.requireNonNull(name, "name");
        async.execute(() -> {
            try {
                bot.submit(announcement(message, milestone, name), Actor.STEWARD, Schedule.within(KEEP));
            } catch (final RuntimeException failure) {
                warn.accept("could not send the announcement of " + milestone, failure);
            }
        });
    }

    BotRequest.Announce announcement(
            final BotRequest.Announce.Message message, final String milestone, final Function<Locale, String> name) {
        final Map<String, String> names = new LinkedHashMap<>();
        for (final Locale locale : locales) {
            names.put(Locales.tag(locale), name.apply(locale));
        }
        return BotRequest.Announce.milestone(message, milestone, names);
    }
}
