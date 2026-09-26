package eu.nordtal.s2.discordbot.access.discord;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.payment.Money;
import eu.nordtal.s2.discordbot.Card;
import eu.nordtal.s2.discordbot.Ids;
import eu.nordtal.s2.discordbot.ManagedMessageDao;
import eu.nordtal.s2.discordbot.access.payment.Tier;
import eu.nordtal.s2.discordbot.access.payment.Tiers;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.jdbi.v3.core.Jdbi;

/**
 * The bot-maintained messages: a contribution message and a link message per configured language.
 *
 * On startup the bot edits the message it posted last time, or posts a new one if there isn't one. The id is
 * remembered in {@code managed_message}, so a restart never leaves a second copy - and the embed is rendered from
 * configuration on every start, which is what stops a stale price from living on in an embed nobody re-posted.
 * Season 1's answer was a {@code /send-contribution-embed} command with the prices, role ids and image URLs written
 * into the source; those image URLs have since expired and render as broken images.
 *
 * How many there are is a config question. It was four - {@code CONTRIBUTION_EN}, {@code CONTRIBUTION_DE},
 * {@code LINK_EN}, {@code LINK_DE}, hard-coded as an enum against four fixed channel keys. It is now two per entry
 * of {@code access.yml} 's {@code languages} list, with the same names: the {@code managed_message.kind} is derived
 * from the language tag, so the rows the bot has already written keep their keys and a third language adds two rows
 * rather than needing a migration. {@code V2__bot_state.sql} left {@code kind} unconstrained for exactly this.
 */
@Slf4j
public final class ManagedMessages {

    private static final String CONTRIBUTION_BANNER = "contribution.png";
    private static final String LINK_BANNER = "link.png";

    private final JDA jda;
    private final Languages languages;
    private final Tiers tiers;
    private final Messages messages;
    private final ManagedMessageDao dao;

    public ManagedMessages(
            final JDA jda, final Languages languages, final Tiers tiers, final Messages messages, final Jdbi jdbi) {
        this.jda = jda;
        this.languages = languages;
        this.tiers = tiers;
        this.messages = messages;
        this.dao = jdbi.onDemand(ManagedMessageDao.class);
    }

    /**
     * Posts or edits two messages per configured language, in the order {@code access.yml} lists them.
     *
     * Failures are logged per message: one bad channel id must not stop the others.
     */
    public void publishAll() {
        for (final Languages.Language language : languages.all()) {
            final Locale locale = language.locale();
            publish(language.contributionKind(), true, language.contributionChannelId(), locale);
            publish(language.linkKind(), false, language.linkChannelId(), locale);
        }
    }

    private void publish(final String kind, final boolean contribution, final String channelId, final Locale locale) {
        // A language with no channel for this message does not get it. Checked first: getChannelById throws on empty.
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

        final MessageEmbed embed = contribution ? contributionEmbed(locale) : linkEmbed(locale);
        final List<ActionRow> components = List.of(ActionRow.of(
                contribution
                        ? Button.primary(
                                Ids.BUY,
                                messages.format(locale, MESSAGES.contribution().button()))
                        // Stage C: opens a modal for the code the proxy showed on the login screen.
                        : Button.primary(
                                Ids.LINK,
                                messages.format(locale, MESSAGES.link().button()))));
        final String banner = contribution ? CONTRIBUTION_BANNER : LINK_BANNER;

        try {
            final Optional<String> existing = dao.messageIdOf(kind, channelId);
            if (existing.isPresent() && edit(channel, existing.get(), embed, components, banner)) {
                return;
            }
            final String posted = channel.sendMessageEmbeds(embed)
                    .addComponents(components)
                    .addFiles(FileUpload.fromData(banner(banner), banner))
                    .complete()
                    .getId();
            dao.remember(kind, channelId, posted);
            log.info("Posted the {} message as {} in {}", kind, posted, channelId);
        } catch (final RuntimeException exception) {
            log.error("Could not maintain the {} message in channel {}", kind, channelId, exception);
        }
    }

    /**
     * @return {@code false} when the remembered message is gone - it was deleted by hand, or the
     *         channel was cleared - so the caller posts a fresh one
     */
    private boolean edit(
            final MessageChannel channel,
            final String messageId,
            final MessageEmbed embed,
            final List<ActionRow> components,
            final String banner) {
        try {
            // setReplace(true) re-uploads the attachment; inheriting the old one would keep swapped-out artwork.
            channel.editMessageById(
                            messageId,
                            new MessageEditBuilder()
                                    .setReplace(true)
                                    .setEmbeds(embed)
                                    .setComponents(components)
                                    .setFiles(FileUpload.fromData(banner(banner), banner))
                                    .build())
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

    // Embeds.

    private MessageEmbed contributionEmbed(final Locale locale) {
        final List<String> prices = new ArrayList<>();
        for (final Tier tier : tiers.all()) {
            prices.add(messages.format(
                    locale, MESSAGES.contribution().tierLine(tier.days(), Money.format(tier.priceCents()))));
        }
        return Card.of(messages.format(locale, MESSAGES.contribution().title()), Card.Accent.NORDTAL)
                .block(messages.format(locale, MESSAGES.contribution().prices()), prices, count -> "+" + count)
                .field(
                        messages.format(locale, MESSAGES.contribution().donationHeading()),
                        messages.format(locale, MESSAGES.contribution().donation(Money.format(tiers.donationCents()))))
                .field(
                        messages.format(locale, MESSAGES.contribution().renewHeading()),
                        messages.format(locale, MESSAGES.contribution().renew()))
                .image("attachment://" + CONTRIBUTION_BANNER)
                .build();
    }

    private MessageEmbed linkEmbed(final Locale locale) {
        return Card.of(messages.format(locale, MESSAGES.link().title()), Card.Accent.NORDTAL)
                .wide(
                        messages.format(locale, MESSAGES.link().stepsHeading()),
                        messages.format(
                                locale,
                                MESSAGES.link()
                                        .steps(messages.format(
                                                locale, MESSAGES.link().button()))))
                .field(
                        messages.format(locale, MESSAGES.link().switchHeading()),
                        messages.format(locale, MESSAGES.link().switchAccount()))
                .image("attachment://" + LINK_BANNER)
                .build();
    }

    // Resources.

    private InputStream banner(final String name) {
        final InputStream stream = getClass().getClassLoader().getResourceAsStream("banners/" + name);
        if (stream == null) {
            throw new IllegalStateException("banners/" + name + " is missing from the jar");
        }
        return stream;
    }
}
