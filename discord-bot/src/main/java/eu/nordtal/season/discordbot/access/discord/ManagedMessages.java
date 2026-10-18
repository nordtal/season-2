package eu.nordtal.season.discordbot.access.discord;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredLayouts;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import eu.nordtal.season.messages.value.Money;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import org.jdbi.v3.core.Jdbi;

/**
 * The bot-maintained messages: a contribution message and a link message per configured language.
 *
 * Each is a {@link ManagedMessage}, so a start edits the last one rather than posting another.
 */
public final class ManagedMessages {

    /** The contribution message: the prices, the donation and the renewal over the banner, the Buy button under it. */
    static final Layout CONTRIBUTION = Layout.discord(
            "access",
            "contribution",
            "Contribution",
            List.of(
                    Element.list("tiers", "access/contribution.tier-line"),
                    Element.button("buy", "access/contribution.button")),
            Part.container(
                    Tone.BRAND,
                    Part.text(Line.title("access/contribution.title")),
                    Part.text(Line.heading("access/contribution.prices"), Line.list("tiers")),
                    Part.text(
                            Line.heading("access/contribution.donation-heading"),
                            Line.text("access/contribution.donation")),
                    Part.text(
                            Line.heading("access/contribution.renew-heading"), Line.text("access/contribution.renew")),
                    Part.gallery("attachment://contribution.png")),
            Part.row("buy"));

    /** The link message: the steps and the switch over the banner, the button that opens the code dialog under it. */
    static final Layout LINK = Layout.discord(
            "access",
            "link",
            "Link",
            List.of(Element.button("link", "access/link.button")),
            Part.container(
                    Tone.BRAND,
                    Part.text(Line.title("access/link.title")),
                    Part.text(Line.heading("access/link.steps-heading"), Line.text("access/link.steps")),
                    Part.text(Line.heading("access/link.switch-heading"), Line.text("access/link.switch-account")),
                    Part.gallery("attachment://link.png")),
            Part.row("link"));

    private final GuildLanguages languages;
    private final Tiers tiers;
    private final LayoutRenderer renderer;
    private final ManagedMessage managed;

    public ManagedMessages(
            final JDA jda,
            final GuildLanguages languages,
            final Tiers tiers,
            final DiscordRenderer messages,
            final StoredLayouts layouts,
            final Jdbi jdbi) {
        this.languages = languages;
        this.tiers = tiers;
        this.renderer = new LayoutRenderer(messages, layouts);
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /**
     * Posts or edits two messages per configured language, in the order the {@code access} group lists them.
     *
     * Failures are logged per message: one bad channel id must not stop the others.
     */
    public void publishAll() {
        for (final GuildLanguages.Language language : languages.all()) {
            final Locale locale = language.locale();
            managed.publish(
                    language.contributionKind(),
                    language.contributionChannelId(),
                    contribution(renderer, tiers, locale));
            managed.publish(language.linkKind(), language.linkChannelId(), link(renderer, locale));
        }
    }

    static LayoutRenderer.Drawn contribution(final LayoutRenderer renderer, final Tiers tiers, final Locale locale) {
        final List<Map<String, Object>> items = tiers.all().stream()
                .map(tier ->
                        Map.<String, Object>of("days", (long) tier.days(), "price", Money.euroCents(tier.priceCents())))
                .toList();
        return renderer.render(
                CONTRIBUTION,
                new LayoutRenderer.Filling(
                        Map.of("amount", Money.euroCents(tiers.donationCents())),
                        Map.of(
                                "tiers", new LayoutRenderer.FilledList(items),
                                "buy", new LayoutRenderer.FilledButton(Ids.BUY))),
                locale);
    }

    static LayoutRenderer.Drawn link(final LayoutRenderer renderer, final Locale locale) {
        return renderer.render(
                LINK,
                new LayoutRenderer.Filling(
                        Map.of("button", MESSAGES.link().button()),
                        // Opens a modal for the code the proxy showed on the login screen.
                        Map.of("link", new LayoutRenderer.FilledButton(Ids.LINK))),
                locale);
    }
}
