package eu.nordtal.s2.discordbot.announce;

import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.messages.MessageRef;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

/**
 * Posts a message into one language's announcement channel, rendered in that language.
 * The bot's inbox calls it for an announcement request, and the bot's status tick through {@link #postAll}.
 */
public final class Announcements {

    private final Channels channels;
    private final Languages languages;
    private final DiscordRenderer messages;
    private final Logger log;

    public Announcements(final JDA jda, final Languages languages, final DiscordRenderer messages, final Logger log) {
        this(Channels.of(jda), languages, messages, log);
    }

    Announcements(
            final Channels channels, final Languages languages, final DiscordRenderer messages, final Logger log) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.languages = Objects.requireNonNull(languages, "languages");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Renders a message in one language, posts it into that language's announcement channel and waits for Discord.
     *
     * @param languageTag the language, as in {@code access#languages[].tag}
     * @return whether it was posted; {@code false} when that language has no channel or Discord refused
     */
    public boolean post(final String languageTag, final MessageRef message) {
        final Optional<Languages.Language> language = languages.byTag(languageTag);
        if (language.isEmpty() || !language.get().hasAnnouncementChannel()) {
            // Not a fault: a language without a channel gets no announcements.
            return false;
        }
        final String text = messages.format(language.get().locale(), message);
        final String channelId = language.get().announcementChannelId();
        final Optional<String> channel = channels.name(channelId);
        if (channel.isEmpty()) {
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
            channels.send(channelId, text);
            log.info("Announced in '{}' in #{} ({}): {}", languageTag, channel.get(), channelId, text);
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

    /** Discord as far as posting a line goes, so a test can stand in for the guild. */
    interface Channels {

        /** Returns the channel's name, or empty when it does not exist or the bot cannot see it. */
        Optional<String> name(String channelId);

        /** Posts the text and waits until Discord took it. */
        void send(String channelId, String text) throws InterruptedException, ExecutionException, TimeoutException;

        /** The guild the bot is logged in to. */
        static Channels of(final JDA jda) {
            return new Channels() {
                @Override
                public Optional<String> name(final String channelId) {
                    return Optional.ofNullable(jda.getChannelById(MessageChannel.class, channelId))
                            .map(MessageChannel::getName);
                }

                @Override
                public void send(final String channelId, final String text)
                        throws InterruptedException, ExecutionException, TimeoutException {
                    final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
                    if (channel == null) {
                        throw new ExecutionException(new IllegalStateException("channel " + channelId + " is gone"));
                    }
                    channel.sendMessage(text).submit().get(POST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                }
            };
        }
    }

    /** Posts one message into every language's channel, each rendered in its language. */
    public void postAll(final MessageRef message) {
        for (final Languages.Language language : languages.all()) {
            if (language.hasAnnouncementChannel()) {
                post(language.tag(), message);
            }
        }
    }
}
