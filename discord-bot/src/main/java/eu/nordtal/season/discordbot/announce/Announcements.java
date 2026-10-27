package eu.nordtal.season.discordbot.announce;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

public final class Announcements {

    /** @param text an admin's words in one language, posted with their markdown */
    record Words(
            @Example("The End opens tonight at **eight**.") String text) {}

    record Milestone(MilestoneContext milestone) {}

    static final DocumentSpec<Words> WORDS = announcement("announce-words", Words.class, "Words an admin wrote");
    static final DocumentSpec<Milestone> MILESTONE = announcement("announce-milestone", Milestone.class, "Milestone");
    static final DocumentSpec<Milestone> BORDER = announcement("announce-border", Milestone.class, "Border");
    static final DocumentSpec<Milestone> NETHER = announcement("announce-nether", Milestone.class, "Nether");
    static final DocumentSpec<Milestone> END = announcement("announce-end", Milestone.class, "End");

    private static <V extends Record> DocumentSpec<V> announcement(
            final String name, final Class<V> values, final String title) {
        return DocumentSpec.plain(StoredDocuments.NAMESPACE, name, "Announcements", values)
                .titled(title);
    }

    /** Returns one line of an announcement as its document draws it in a reader's language. */
    public static Function<Locale, String> text(
            final DocumentRenderer documents,
            final BotRequest.Announce announcement,
            final BotRequest.Announce.Line line) {
        if (announcement.message() == BotRequest.Announce.Message.WORDS) {
            final Words words = new Words(Objects.requireNonNull(line.words(), "words"));
            return locale -> documents.plain(WORDS, words, locale);
        }
        final DocumentSpec<Milestone> spec = switch (announcement.message()) {
            case MILESTONE_BORDER -> BORDER;
            case MILESTONE_NETHER -> NETHER;
            case MILESTONE_END -> END;
            case MILESTONE, WORDS -> MILESTONE;
        };
        final Milestone milestone =
                new Milestone(new MilestoneContext(Objects.requireNonNull(line.milestoneName(), "milestoneName")));
        return locale -> documents.plain(spec, milestone, locale);
    }

    private final Channels channels;
    private final GuildLanguages languages;
    private final Logger log;

    public Announcements(final JDA jda, final GuildLanguages languages, final Logger log) {
        this(Channels.of(jda), languages, log);
    }

    Announcements(final Channels channels, final GuildLanguages languages, final Logger log) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.languages = Objects.requireNonNull(languages, "languages");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Renders a text in one language, posts it into that language's announcement channel and waits for Discord.
     *
     * @param languageTag the language, as in {@code access#languages[].tag}
     * @return whether it was posted, {@code false} when that language has no channel or Discord refused
     */
    public boolean post(final String languageTag, final Function<Locale, String> message) {
        final Optional<GuildLanguages.Language> language = languages.byTag(languageTag);
        if (language.isEmpty() || !language.get().hasAnnouncementChannel()) {
            return false;
        }
        final String text = message.apply(language.get().locale());
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

    interface Channels {

        Optional<String> name(String channelId);

        void send(String channelId, String text) throws InterruptedException, ExecutionException, TimeoutException;

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

    public void postAll(final Function<Locale, String> message) {
        for (final GuildLanguages.Language language : languages.all()) {
            if (language.hasAnnouncementChannel()) {
                post(language.tag(), message);
            }
        }
    }
}
