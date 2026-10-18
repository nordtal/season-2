package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Money;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import org.jdbi.v3.core.Jdbi;

public final class ManagedMessages {

    /** @param amount the donation that gives the donor role */
    record Contribution(Money amount) {}

    record Tier(@Example("30") long days, Money price) {}

    static final DocumentSpec<Contribution> CONTRIBUTION = DocumentSpec.message(
            StoredDocuments.NAMESPACE,
            "contribution",
            "Contribution",
            Contribution.class,
            Slot.list("tiers", Tier.class),
            Slot.button("buy"));

    static final DocumentSpec<DocumentSpec.None> LINK =
            DocumentSpec.message(StoredDocuments.NAMESPACE, "link", "Link", Slot.button("link"));

    private final GuildLanguages languages;
    private final Tiers tiers;
    private final DocumentRenderer renderer;
    private final ManagedMessage managed;

    public ManagedMessages(
            final JDA jda,
            final GuildLanguages languages,
            final Tiers tiers,
            final DiscordRenderer messages,
            final StoredDocuments documents,
            final Jdbi jdbi) {
        this.languages = languages;
        this.tiers = tiers;
        this.renderer = new DocumentRenderer(messages, documents);
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /**
     * Posts or edits two messages per configured language, in the order the {@code access} group lists them.
     * Failures are logged per message, so one bad channel id does not stop the others.
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

    static DocumentRenderer.Drawn contribution(
            final DocumentRenderer renderer, final Tiers tiers, final Locale locale) {
        return renderer.render(
                CONTRIBUTION,
                new Contribution(Money.euroCents(tiers.donationCents())),
                Map.of(
                        "tiers",
                        DocumentRenderer.FilledList.of(tiers.all().stream()
                                .map(tier -> new Tier(tier.days(), Money.euroCents(tier.priceCents())))
                                .toList()),
                        "buy",
                        new DocumentRenderer.FilledButton(Ids.BUY)),
                locale);
    }

    static DocumentRenderer.Drawn link(final DocumentRenderer renderer, final Locale locale) {
        return renderer.render(
                LINK, new DocumentSpec.None(), Map.of("link", new DocumentRenderer.FilledButton(Ids.LINK)), locale);
    }
}
