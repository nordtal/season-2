package eu.nordtal.s2.discordbot.announce;

import eu.nordtal.s2.commands.announce.AnnounceEffects;
import eu.nordtal.s2.discordbot.config.Languages;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

/**
 * Posts {@code announce <language> <text>} lines into that language's announcement channel.
 *
 * The text arrives finished; the command inbox calls it inline, and the bot's status tick through {@link #postAll}.
 */
public final class Announcements implements AnnounceEffects {

    private final JDA jda;
    private final Languages languages;
    private final Executor executor;
    private final Logger log;

    public Announcements(final JDA jda, final Languages languages, final Executor executor, final Logger log) {
        this.jda = Objects.requireNonNull(jda, "jda");
        this.languages = Objects.requireNonNull(languages, "languages");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable cause) {
        log.warn(what, cause);
    }

    @Override
    public boolean post(final String languageTag, final String text) {
        final Optional<Languages.Language> language = languages.byTag(languageTag);
        if (language.isEmpty() || !language.get().hasAnnouncementChannel()) {
            // Not a fault: a language without a channel gets no announcements.
            return false;
        }
        final String channelId = language.get().announcementChannelId();
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error(
                    "Announcement channel {} for '{}' does not exist or the bot cannot see it;"
                            + " it would have carried \"{}\"",
                    channelId,
                    languageTag,
                    text);
            return false;
        }
        // Waited for, so "posted" means Discord took it; every caller is a worker thread.
        try {
            channel.sendMessage(text).submit().get(POST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            log.debug("Announced in '{}': {}", languageTag, text);
            return true;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while announcing in '{}'", languageTag);
            return false;
        } catch (final ExecutionException | TimeoutException failure) {
            // A timeout counts as a failure even though JDA may still deliver the message.
            log.warn(
                    "Could not announce in '{}': {} - it would have carried \"{}\"",
                    languageTag,
                    failure.toString(),
                    text);
            return false;
        }
    }

    private static final java.time.Duration POST_TIMEOUT = java.time.Duration.ofSeconds(15);

    /**
     * Posts one line per language that has a channel.
     *
     * @param render the line for one language
     */
    public void postAll(final java.util.function.Function<Languages.Language, String> render) {
        for (final Languages.Language language : languages.all()) {
            if (language.hasAnnouncementChannel()) {
                post(language.tag(), render.apply(language));
            }
        }
    }
}
