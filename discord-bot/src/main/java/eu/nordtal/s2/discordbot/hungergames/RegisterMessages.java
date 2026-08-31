package eu.nordtal.s2.discordbot.hungergames;

import eu.nordtal.s2.discordbot.discord.ManagedMessageDao;

import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The bot-maintained Register message: one per configured language, in
 * {@code Language#hungerGamesChannelId()}, carrying the button {@link RegisterFlow} listens for.
 * <p>
 * Same post-or-edit-by-remembered-id shape as {@code ManagedMessages}, reusing the same
 * {@link ManagedMessageDao} - {@code managed_message.kind} is deliberately unconstrained so a
 * second feature maintaining its own kind of managed message needs no migration. This class does
 * not reuse {@code ManagedMessages} itself: that class's embeds are access-specific (tiers,
 * donation, link), and the two features have nothing in common past "post or edit one message per
 * language" - which is exactly what the shared DAO already captures.
 * </p>
 */
@Slf4j
public final class RegisterMessages {

    /** nordtal blue, the same value every other managed embed uses. */
    private static final int COLOUR = 0x3459_74;

    private final JDA jda;
    private final Languages languages;
    private final Messages messages;
    private final ManagedMessageDao dao;

    public RegisterMessages(final JDA jda, final Languages languages, final Messages messages, final Jdbi jdbi) {
        this.jda = jda;
        this.languages = languages;
        this.messages = messages;
        this.dao = jdbi.onDemand(ManagedMessageDao.class);
    }

    /** Posts or edits the Register message in every configured language's channel. */
    public void publishAll() {
        for (final Languages.Language language : languages.all()) {
            publish(language.hungerGamesRegisterKind(), language.hungerGamesChannelId(), language.locale());
        }
    }

    private void publish(final String kind, final String channelId, final Locale locale) {
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Channel {} for the {} message does not exist, or the bot cannot see it. "
                    + "That message is not being maintained.", channelId, kind);
            return;
        }

        final MessageEmbed embed = registerEmbed(locale);
        final List<ActionRow> components = List.of(ActionRow.of(
                Button.primary(Ids.REGISTER, messages.get(locale, "register.button"))));

        try {
            final Optional<String> existing = dao.messageIdOf(kind, channelId);
            if (existing.isPresent() && edit(channel, existing.get(), embed, components)) {
                return;
            }
            final String posted = channel.sendMessageEmbeds(embed).addComponents(components).complete().getId();
            dao.remember(kind, channelId, posted);
            log.info("Posted the {} message as {} in {}", kind, posted, channelId);
        } catch (final RuntimeException exception) {
            log.error("Could not maintain the {} message in channel {}", kind, channelId, exception);
        }
    }

    private boolean edit(final MessageChannel channel, final String messageId, final MessageEmbed embed,
                         final List<ActionRow> components) {
        try {
            channel.editMessageById(messageId, new MessageEditBuilder()
                    .setEmbeds(embed)
                    .setComponents(components)
                    .build()).complete();
            return true;
        } catch (final RuntimeException exception) {
            log.info("The remembered message {} in {} could not be edited ({}); posting a new one",
                    messageId, channel.getId(), exception.toString());
            return false;
        }
    }

    private MessageEmbed registerEmbed(final Locale locale) {
        return new EmbedBuilder()
                .setColor(COLOUR)
                .setTitle(messages.get(locale, "register.title"))
                .setDescription(messages.get(locale, "register.body"))
                .build();
    }
}
