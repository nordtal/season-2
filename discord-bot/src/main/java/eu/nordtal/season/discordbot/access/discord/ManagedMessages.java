package eu.nordtal.season.discordbot.access.discord;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.Card;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.config.Languages;
import eu.nordtal.season.messages.value.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jdbi.v3.core.Jdbi;

/**
 * The bot-maintained messages: a contribution message and a link message per configured language.
 *
 * Each is a {@link ManagedMessage}, so a start edits the last one rather than posting another.
 */
public final class ManagedMessages {

    private static final String CONTRIBUTION_BANNER = "contribution.png";
    private static final String LINK_BANNER = "link.png";

    private final Languages languages;
    private final Tiers tiers;
    private final DiscordRenderer messages;
    private final ManagedMessage managed;

    public ManagedMessages(
            final JDA jda,
            final Languages languages,
            final Tiers tiers,
            final DiscordRenderer messages,
            final Jdbi jdbi) {
        this.languages = languages;
        this.tiers = tiers;
        this.messages = messages;
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /**
     * Posts or edits two messages per configured language, in the order the {@code access} group lists them.
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
        final MessageEmbed embed = contribution ? contributionEmbed(locale) : linkEmbed(locale);
        final List<ActionRow> components = List.of(ActionRow.of(
                contribution
                        ? Button.primary(
                                Ids.BUY,
                                messages.format(locale, MESSAGES.contribution().button()))
                        // Opens a modal for the code the proxy showed on the login screen.
                        : Button.primary(
                                Ids.LINK,
                                messages.format(locale, MESSAGES.link().button()))));
        final String banner = contribution ? CONTRIBUTION_BANNER : LINK_BANNER;
        managed.publish(kind, channelId, new ManagedMessage.Content(List.of(embed), components, banner));
    }

    private MessageEmbed contributionEmbed(final Locale locale) {
        final List<String> prices = new ArrayList<>();
        for (final Tier tier : tiers.all()) {
            prices.add(messages.format(
                    locale, MESSAGES.contribution().tierLine(tier.days(), Money.euroCents(tier.priceCents()))));
        }
        return Card.of(messages.format(locale, MESSAGES.contribution().title()))
                .block(messages.format(locale, MESSAGES.contribution().prices()), prices, count -> "+" + count)
                .field(
                        messages.format(locale, MESSAGES.contribution().donationHeading()),
                        messages.format(
                                locale, MESSAGES.contribution().donation(Money.euroCents(tiers.donationCents()))))
                .field(
                        messages.format(locale, MESSAGES.contribution().renewHeading()),
                        messages.format(locale, MESSAGES.contribution().renew()))
                .image("attachment://" + CONTRIBUTION_BANNER)
                .build();
    }

    private MessageEmbed linkEmbed(final Locale locale) {
        return Card.of(messages.format(locale, MESSAGES.link().title()))
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
}
