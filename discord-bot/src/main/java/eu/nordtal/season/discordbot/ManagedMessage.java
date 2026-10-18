package eu.nordtal.season.discordbot;

import eu.nordtal.season.discordbot.config.Configured;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jdbi.v3.core.Jdbi;

/**
 * Keeps one bot message per kind in its channel, editing the one it posted last rather than posting another.
 * The id is kept in {@code managed_message}; a remembered message that is gone is posted anew. An edit turns an embed
 * message into Components V2, which Discord never turns back.
 */
@Slf4j
public final class ManagedMessage {

    private final JDA jda;
    private final ManagedMessageDao dao;

    public ManagedMessage(final JDA jda, final Jdbi jdbi) {
        this.jda = jda;
        this.dao = jdbi.onDemand(ManagedMessageDao.class);
    }

    /**
     * Posts or edits the message of {@code kind} in its channel; an empty channel id means no message.
     *
     * A failure is logged and never thrown, so one bad channel does not stop the others.
     */
    public void publish(final String kind, final String channelId, final LayoutRenderer.Drawn content) {
        // Checked first: getChannelById throws on an empty id.
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
            final String posted = channel.sendMessageComponents(content.components())
                    .useComponentsV2()
                    .addFiles(uploads(content))
                    .complete()
                    .getId();
            dao.remember(kind, channelId, posted);
            log.info("Posted the {} message as {} in {}", kind, posted, channelId);
        } catch (final RuntimeException exception) {
            log.error("Could not maintain the {} message in channel {}", kind, channelId, exception);
        }
    }

    /** Returns {@code false} when the remembered message is gone, so the caller posts a fresh one. */
    private boolean edit(final MessageChannel channel, final String messageId, final LayoutRenderer.Drawn content) {
        try {
            channel.editMessageById(messageId, edit(content)).complete();
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

    /**
     * The edit that turns a message into {@code content}: Components V2 with its embeds emptied, as Discord requires.
     * The images are uploaded anew and replace the old ones, so a swapped banner shows.
     */
    static MessageEditData edit(final LayoutRenderer.Drawn content) {
        return new MessageEditBuilder()
                .setEmbeds(List.of())
                .useComponentsV2()
                .setComponents(content.components())
                .setFiles(uploads(content))
                .build();
    }

    private static List<FileUpload> uploads(final LayoutRenderer.Drawn content) {
        final List<FileUpload> all = new ArrayList<>(
                content.attachments().stream().map(ManagedMessage::upload).toList());
        all.addAll(content.uploads());
        return all;
    }

    private static FileUpload upload(final String banner) {
        final InputStream stream = ManagedMessage.class.getClassLoader().getResourceAsStream("banners/" + banner);
        if (stream == null) {
            throw new IllegalStateException("banners/" + banner + " is missing from the jar");
        }
        return FileUpload.fromData(stream, banner);
    }
}
