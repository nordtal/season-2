package eu.nordtal.s2.discordbot.announce;

import eu.nordtal.s2.commands.announce.AnnounceEffects;
import eu.nordtal.s2.discordbot.config.Languages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * The bot's end of {@code announce <language> <text>}: post the line into that language's
 * announcement channel.
 *
 * <p>Two callers. The command inbox, for lines a server sent as a {@code command_request} row -
 * a milestone, a farm-reset warning - with {@code Runnable::run} as the executor because the inbox
 * settles the row when the command returns. And the bot itself, for a phase change it noticed on
 * its own status tick, through {@link #postAll}. Neither renders anything: the text arrives
 * finished, in the language of the channel it goes into.</p>
 */
public final class Announcements implements AnnounceEffects {

    private final JDA jda;
    private final Languages languages;
    private final Executor executor;
    private final Logger log;

    public Announcements(final JDA jda, final Languages languages, final Executor executor,
                         final Logger log) {
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
            // The default, and not a fault: a language without a channel gets no announcements.
            return false;
        }
        final String channelId = language.get().announcementChannelId();
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Announcement channel {} for '{}' does not exist or the bot cannot see it;"
                    + " it would have carried \"{}\"", channelId, languageTag, text);
            return false;
        }
        channel.sendMessage(text).queue(
                sent -> log.debug("Announced in '{}': {}", languageTag, text),
                failure -> log.warn("Could not announce in '{}': {}", languageTag, failure.toString()));
        return true;
    }

    /**
     * Posts one line per language that has a channel - for a moment the bot noticed itself.
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
