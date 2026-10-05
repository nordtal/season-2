package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.discordbot.config.Configured;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one bot message per kind in its channel, editing the one it posted last rather than posting another.
 *
 * The id is kept in {@code managed_message}; a remembered message that is gone is posted anew.
 */
@Slf4j
public final class ManagedMessage {

    /** What one message shows: its embeds, its buttons, and the image it attaches from {@code banners/}, if any. */
    public record Content(
            List<MessageEmbed> embeds,
            List<ActionRow> components,
            @Nullable String banner) {}

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
    public void publish(final String kind, final String channelId, final Content content) {
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
            final MessageCreateAction post =
                    channel.sendMessageEmbeds(content.embeds()).addComponents(content.components());
            final String posted = (content.banner() == null ? post : post.addFiles(upload(content.banner())))
                    .complete()
                    .getId();
            dao.remember(kind, channelId, posted);
            log.info("Posted the {} message as {} in {}", kind, posted, channelId);
        } catch (final RuntimeException exception) {
            log.error("Could not maintain the {} message in channel {}", kind, channelId, exception);
        }
    }

    /** Returns {@code false} when the remembered message is gone, so the caller posts a fresh one. */
    private boolean edit(final MessageChannel channel, final String messageId, final Content content) {
        try {
            // setReplace(true) re-uploads the attachment; inheriting the old one would keep swapped-out artwork.
            final MessageEditBuilder edit = new MessageEditBuilder()
                    .setReplace(true)
                    .setEmbeds(content.embeds())
                    .setComponents(content.components());
            channel.editMessageById(
                            messageId,
                            (content.banner() == null ? edit : edit.setFiles(upload(content.banner()))).build())
                    .complete();
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

    private FileUpload upload(final String banner) {
        final InputStream stream = getClass().getClassLoader().getResourceAsStream("banners/" + banner);
        if (stream == null) {
            throw new IllegalStateException("banners/" + banner + " is missing from the jar");
        }
        return FileUpload.fromData(stream, banner);
    }
}
