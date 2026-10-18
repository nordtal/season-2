package eu.nordtal.season.discordbot;

import eu.nordtal.season.discordbot.config.Configured;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jdbi.v3.core.Jdbi;

@Slf4j
public final class ManagedMessage {

    private final JDA jda;
    private final ManagedMessageDao dao;

    public ManagedMessage(final JDA jda, final Jdbi jdbi) {
        this.jda = jda;
        this.dao = jdbi.onDemand(ManagedMessageDao.class);
    }

    /**
     * Posts or edits the message of {@code kind} in its channel.
     * An empty channel id means no message.
     * A failure is logged and never thrown, so one bad channel does not stop the others.
     */
    public void publish(final String kind, final String channelId, final DocumentRenderer.Drawn content) {
        if (!Configured.isSet(channelId)) {
            return;
        }
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error(
                    "Channel {} for the {} message does not exist, or the bot cannot see it. "
                            + "That message is not being maintained.",
                    channelId,
                    kind);
            return;
        }
        try {
            final Optional<String> existing = dao.messageIdOf(kind, channelId);
            if (existing.isPresent() && edit(channel, existing.get(), content)) {
                return;
            }
            final String posted =
                    channel.sendMessage(content.create()).complete().getId();
            dao.remember(kind, channelId, posted);
            log.info("Posted the {} message as {} in {}", kind, posted, channelId);
        } catch (final RuntimeException exception) {
            log.error("Could not maintain the {} message in channel {}", kind, channelId, exception);
        }
    }

    private boolean edit(final MessageChannel channel, final String messageId, final DocumentRenderer.Drawn content) {
        try {
            channel.editMessageById(messageId, content.edit()).complete();
            return true;
        } catch (final RuntimeException exception) {
            log.info(
                    "The remembered message {} in {} could not be edited ({}); posting a new one",
                    messageId,
                    channel.getId(),
                    exception.toString());
            return false;
        }
    }
}
