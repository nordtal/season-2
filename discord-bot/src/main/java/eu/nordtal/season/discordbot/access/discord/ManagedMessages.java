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

    static final DocumentSpec<Contribution> LINK = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "link",
                    "Link",
                    Contribution.class,
                    Slot.list("tiers", Tier.class),
                    Slot.button("buy"),
                    Slot.button("link"))
            .titled("Join, prices and account link");

    static final DocumentSpec<DocumentSpec.None> RULES = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "rules",
                    "Rules",
                    Slot.link("join").optional(),
                    Slot.link("news").optional())
            .titled("Rules and season overview");

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
     * Any leftover contribution or guide message is retired. Failures are logged per message.
     */
    public void publishAll() {
        for (final GuildLanguages.Language language : languages.all()) {
            final Locale locale = language.locale();
            if (managed.publish(language.linkKind(), language.linkChannelId(), link(renderer, tiers, locale))) {
                managed.retire(language.contributionKind());
            }
            if (managed.publish(
                    language.rulesKind(), language.rulesChannelId(), rules(renderer, language, guildOf(language)))) {
                managed.retire(language.guideKind());
            }
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

    static DocumentRenderer.Drawn link(final DocumentRenderer renderer, final Tiers tiers, final Locale locale) {
        return renderer.render(
                LINK,
                new Contribution(Money.euroCents(tiers.donationCents())),
                Map.of(
                        "tiers",
                        DocumentRenderer.FilledList.of(tiers.all().stream()
                                .map(tier -> new Tier(tier.days(), Money.euroCents(tier.priceCents())))
                                .toList()),
                        "buy",
                        new DocumentRenderer.FilledButton(Ids.BUY),
                        "link",
                        new DocumentRenderer.FilledButton(Ids.LINK)),
                locale);
    }

    /** Draws the rules and season overview, whose buttons open the join and news channels where configured. */
    static DocumentRenderer.Drawn rules(
            final DocumentRenderer renderer, final GuildLanguages.Language language, final String guild) {
        return renderer.render(
                RULES,
                new DocumentSpec.None(),
                Map.of(
                        "join", channel(guild, language.linkChannelId()),
                        "news", channel(guild, language.announcementChannelId())),
                language.locale());
    }

    private static DocumentRenderer.Filled channel(final String guild, final String channel) {
        return Configured.isSet(guild) && Configured.isSet(channel)
                ? DocumentRenderer.FilledButton.link("https://discord.com/channels/" + guild + "/" + channel)
                : new DocumentRenderer.FilledNothing();
    }
}
