package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.discordbot.bunq.Money;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.payment.Tier;
import eu.nordtal.s2.discordbot.payment.Tiers;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.jdbi.v3.core.Jdbi;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The four bot-maintained messages: contribution DE/EN and link DE/EN.
 * <p>
 * On startup the bot <b>edits</b> the message it posted last time, or posts a new one if there
 * isn't one. The id is remembered in {@code managed_message}, so a restart never leaves a second
 * copy - and the embed is rendered from configuration on every start, which is what stops a stale
 * price from living on in an embed nobody re-posted. Season 1's answer was a
 * {@code /send-contribution-embed} command with the prices, role ids and image URLs written into
 * the source; those image URLs have since expired and render as broken images.
 * </p>
 */
@Slf4j
public final class ManagedMessages {

    /** nordtal blue, the same value season 1's embeds used. */
    private static final int COLOUR = 0x3459_74;

    private static final String CONTRIBUTION_BANNER = "contribution.png";
    private static final String LINK_BANNER = "link.png";

    private final JDA jda;
    private final AccessSpec config;
    private final Tiers tiers;
    private final Messages messages;
    private final ManagedMessageDao dao;

    public ManagedMessages(final JDA jda, final AccessSpec config, final Tiers tiers,
                           final Messages messages, final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
        this.tiers = tiers;
        this.messages = messages;
        this.dao = jdbi.onDemand(ManagedMessageDao.class);
    }

    /** Posts or edits all four. Failures are logged per message: one bad channel id must not stop the others. */
    public void publishAll() {
        publish(Kind.CONTRIBUTION_EN, config.channels().contributionEn(), Locale.ENGLISH);
        publish(Kind.CONTRIBUTION_DE, config.channels().contributionDe(), Locale.GERMAN);
        publish(Kind.LINK_EN, config.channels().linkEn(), Locale.ENGLISH);
        publish(Kind.LINK_DE, config.channels().linkDe(), Locale.GERMAN);
    }

    private void publish(final Kind kind, final String channelId, final Locale locale) {
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Channel {} for the {} message does not exist, or the bot cannot see it. "
                    + "That message is not being maintained.", channelId, kind);
            return;
        }

        final MessageEmbed embed = kind.contribution() ? contributionEmbed(locale) : linkEmbed(locale);
        final List<ActionRow> components = List.of(ActionRow.of(kind.contribution()
                ? Button.primary(Ids.BUY, messages.get(locale, "contribution.button"))
                // Stage C: opens a modal for the code the proxy showed on the login screen.
                : Button.primary(Ids.LINK, messages.get(locale, "link.button"))));
        final String banner = kind.contribution() ? CONTRIBUTION_BANNER : LINK_BANNER;

        try {
            final Optional<String> existing = dao.messageIdOf(kind.name(), channelId);
            if (existing.isPresent() && edit(channel, existing.get(), embed, components, banner)) {
                return;
            }
            final String posted = channel.sendMessageEmbeds(embed)
                    .addComponents(components)
                    .addFiles(FileUpload.fromData(banner(banner), banner))
                    .complete()
                    .getId();
            dao.remember(kind.name(), channelId, posted);
            log.info("Posted the {} message as {} in {}", kind, posted, channelId);
        } catch (final RuntimeException exception) {
            log.error("Could not maintain the {} message in channel {}", kind, channelId, exception);
        }
    }

    /**
     * @return {@code false} when the remembered message is gone - it was deleted by hand, or the
     *         channel was cleared - so the caller posts a fresh one
     */
    private boolean edit(final MessageChannel channel, final String messageId, final MessageEmbed embed,
                         final List<ActionRow> components, final String banner) {
        try {
            // setReplace(true) so the attachment is re-uploaded rather than inherited: the embed
            // points at attachment://<banner>, and an edit that leaves the old attachment in place
            // would keep whatever image was there before the artwork was swapped.
            channel.editMessageById(messageId, new MessageEditBuilder()
                    .setReplace(true)
                    .setEmbeds(embed)
                    .setComponents(components)
                    .setFiles(FileUpload.fromData(banner(banner), banner))
                    .build()).complete();
            return true;
        } catch (final RuntimeException exception) {
            log.info("The remembered message {} in {} could not be edited ({}); posting a new one",
                    messageId, channel.getId(), exception.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------- embeds

    private MessageEmbed contributionEmbed(final Locale locale) {
        final StringBuilder prices = new StringBuilder();
        for (final Tier tier : tiers.all()) {
            prices.append(messages.format(locale, "contribution.tier-line",
                    "days", tier.days(), "price", Money.format(tier.priceCents()))).append('\n');
        }

        return new EmbedBuilder()
                .setColor(COLOUR)
                .setTitle(messages.get(locale, "contribution.title"))
                .setDescription(messages.get(locale, "contribution.body")
                        + "\n\n" + messages.format(locale, "contribution.donation",
                        "amount", Money.format(tiers.donationCents()))
                        + "\n\n" + messages.get(locale, "contribution.renew"))
                .addField(messages.get(locale, "contribution.prices"), prices.toString().strip(), false)
                .setImage("attachment://" + CONTRIBUTION_BANNER)
                .build();
    }

    private MessageEmbed linkEmbed(final Locale locale) {
        return new EmbedBuilder()
                .setColor(COLOUR)
                .setTitle(messages.get(locale, "link.title"))
                .setDescription(messages.get(locale, "link.body")
                        + "\n\n" + messages.get(locale, "link.unlink-hint"))
                .setImage("attachment://" + LINK_BANNER)
                .build();
    }

    private InputStream banner(final String name) {
        final InputStream stream = getClass().getClassLoader().getResourceAsStream("banners/" + name);
        if (stream == null) {
            throw new IllegalStateException("banners/" + name + " is missing from the jar");
        }
        return stream;
    }

    /** The four managed messages. The name is the primary key of {@code managed_message}. */
    private enum Kind {
        CONTRIBUTION_EN,
        CONTRIBUTION_DE,
        LINK_EN,
        LINK_DE;

        boolean contribution() {
            return this == CONTRIBUTION_EN || this == CONTRIBUTION_DE;
        }
    }
}
