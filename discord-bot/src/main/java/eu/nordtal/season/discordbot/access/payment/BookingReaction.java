package eu.nordtal.season.discordbot.access.payment;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.access.SeasonStart;
import eu.nordtal.season.discordbot.access.discord.AccessRoles;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.value.Money;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

@Slf4j
public final class BookingReaction {

    private final GuildLanguages languages;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final DocumentRenderer documents;
    private final JDA jda;
    private final SeasonStart seasonStart;

    public BookingReaction(
            final GuildLanguages languages,
            final AccessRoles roles,
            final AdminLog admin,
            final DocumentRenderer documents,
            final JDA jda,
            final SeasonStart seasonStart) {
        this.languages = languages;
        this.roles = roles;
        this.admin = admin;
        this.documents = documents;
        this.jda = jda;
        this.seasonStart = seasonStart;
    }

    public void tell(final BotRequest.PaymentBooked booked) {
        final DiscordId payer = booked.person();
        seasonStart.warnIfUnanchored(payer, booked.from());
        final Locale locale = roles.localeOf(payer);
        roles.applyAccessRole(payer, true);
        if (booked.donation()) {
            roles.grantDonorRole(payer);
        }

        roles.dm(
                payer,
                booked.downgraded()
                        ? new DocumentRenderer.Reply<>(
                                Replies.DM_GRANTED_SHORT,
                                new Replies.Paid(
                                        Money.euroCents(Objects.requireNonNull(
                                                booked.receivedCents(), "a short payment has an amount")),
                                        booked.days(),
                                        booked.until()))
                        : new DocumentRenderer.Reply<>(Replies.DM_GRANTED, new Replies.Until(booked.until())));
        if (booked.donation()) {
            roles.dm(payer, DocumentRenderer.Reply.of(Replies.DM_DONOR));
            announceDonation(payer, booked.donationCents(), locale);
        }

        admin.show(booked.entry());
        log.info("Told {} of {}: {} days", payer, booked.reference(), booked.days());
    }

    private void announceDonation(final DiscordId discordId, final int donationCents, final Locale locale) {
        final String channelId = languages.forLocale(locale).contributionChannelId();
        if (!Configured.isSet(channelId)) {
            return;
        }
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Contribution channel {} is not available; the thank-you was not posted", channelId);
            return;
        }
        channel.sendMessage(documents
                        .reply(
                                locale,
                                new DocumentRenderer.Reply<>(
                                        Replies.PUBLIC_DONATION,
                                        new Replies.Donated(
                                                new DiscordMemberContext(discordId, discordId.value()),
                                                Money.euroCents(donationCents))))
                        .create())
                .queue(ok -> {}, failure -> log.error("Could not post the donation thank-you", failure));
    }
}
