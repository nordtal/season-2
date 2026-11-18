package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.Configured;
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
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.jdbi.v3.core.Jdbi;

public final class ManagedMessages {

    /** @param amount the donation that gives the donor role */
    record Contribution(@Example("500") Money amount) {}

    record Tier(@Example("30") long days, @Example("500") Money price) {}

    static final DocumentSpec<Contribution> CONTRIBUTION = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "contribution",
                    "Contribution",
                    Contribution.class,
                    Slot.list("tiers", Tier.class),
                    Slot.button("buy"))
            .titled("Prices and buy button");

    static final DocumentSpec<DocumentSpec.None> LINK = DocumentSpec.message(
                    StoredDocuments.NAMESPACE, "link", "Link", Slot.button("link"))
            .titled("Link instructions and button");

    static final DocumentSpec<DocumentSpec.None> RULES =
            DocumentSpec.message(StoredDocuments.NAMESPACE, "rules", "Rules").titled("Rules in the rules channel");

    static final DocumentSpec<DocumentSpec.None> GUIDE = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "guide",
                    "Guide",
                    Slot.link("link").optional(),
                    Slot.link("access").optional())
            .titled("Steps to play, under the rules");

    private final JDA jda;

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
        this.jda = jda;
        this.languages = languages;
        this.tiers = tiers;
        this.renderer = new DocumentRenderer(messages, documents);
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /**
     * Posts or edits the messages of each configured language, in the order the {@code access} group lists them.
     * The rules channel gets the rules, then the guide. Failures are logged per message.
     */
    public void publishAll() {
        for (final GuildLanguages.Language language : languages.all()) {
            final Locale locale = language.locale();
            managed.publish(
                    language.contributionKind(),
                    language.contributionChannelId(),
                    contribution(renderer, tiers, locale));
            managed.publish(language.linkKind(), language.linkChannelId(), link(renderer, locale));
            managed.publish(language.rulesKind(), language.rulesChannelId(), rules(renderer, locale));
            managed.publish(
                    language.guideKind(), language.rulesChannelId(), guide(renderer, language, guildOf(language)));
        }
    }

    /** Returns the id of the server that holds the language's rules channel, or an empty string. */
    private String guildOf(final GuildLanguages.Language language) {
        if (!Configured.isSet(language.rulesChannelId())) {
            return "";
        }
        final GuildChannel channel = jda.getGuildChannelById(language.rulesChannelId());
        return channel == null ? "" : channel.getGuild().getId();
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

    static DocumentRenderer.Drawn rules(final DocumentRenderer renderer, final Locale locale) {
        return renderer.render(RULES, new DocumentSpec.None(), Map.of(), locale);
    }

    /** Draws the guide, whose buttons open the language's link and contribution channels where both are known. */
    static DocumentRenderer.Drawn guide(
            final DocumentRenderer renderer, final GuildLanguages.Language language, final String guild) {
        return renderer.render(
                GUIDE,
                new DocumentSpec.None(),
                Map.of(
                        "link", channel(guild, language.linkChannelId()),
                        "access", channel(guild, language.contributionChannelId())),
                language.locale());
    }

    private static DocumentRenderer.Filled channel(final String guild, final String channel) {
        return Configured.isSet(guild) && Configured.isSet(channel)
                ? DocumentRenderer.FilledButton.link("https://discord.com/channels/" + guild + "/" + channel)
                : new DocumentRenderer.FilledNothing();
    }

    static DocumentRenderer.Drawn link(final DocumentRenderer renderer, final Locale locale) {
        return renderer.render(
                LINK, new DocumentSpec.None(), Map.of("link", new DocumentRenderer.FilledButton(Ids.LINK)), locale);
    }
}
