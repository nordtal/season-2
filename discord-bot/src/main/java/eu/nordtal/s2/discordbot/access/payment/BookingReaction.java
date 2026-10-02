package eu.nordtal.s2.discordbot.access.payment;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.payment.Money;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.DiscordMemberContext;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Tells a payer what steward booked: the roles, the direct messages, the public thank-you and the admin note.
 * The booking is committed before the bot hears of it; nothing here writes a payment.
 */
@Slf4j
public final class BookingReaction {

    private final Languages languages;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final Messages messages;
    private final JDA jda;
    private final SeasonStart seasonStart;

    public BookingReaction(
            final Languages languages,
            final AccessRoles roles,
            final AdminLog admin,
            final Messages messages,
            final JDA jda,
            final SeasonStart seasonStart) {
        this.languages = languages;
        this.roles = roles;
        this.admin = admin;
        this.messages = messages;
        this.jda = jda;
        this.seasonStart = seasonStart;
    }

    /** Carries out everything the payer and the admins are told of one booking. */
    public void tell(final BotRequest.PaymentBooked booked) {
        final DiscordId payer = booked.person();
        seasonStart.warnIfUnanchored(payer, booked.from());
        final Locale locale = roles.localeOf(payer);
        roles.applyAccessRole(payer, true);
        if (booked.donation()) {
            roles.grantDonorRole(payer);
        }

        // "Downgraded" means the payer edited the amount down, which the DM says plainly.
        roles.dm(
                payer,
                booked.downgraded()
                        ? messages.format(
                                locale,
                                MESSAGES.dm()
                                        .grantedSection()
                                        .shortMessage(
                                                Money.format(Objects.requireNonNull(
                                                        booked.receivedCents(), "a short payment has an amount")),
                                                booked.days(),
                                                AccessRoles.timestamp(booked.until())))
                        : messages.format(locale, MESSAGES.dm().granted(AccessRoles.timestamp(booked.until()))));
        if (booked.donation()) {
            roles.dm(payer, messages.format(locale, MESSAGES.dm().donor()));
            announceDonation(payer, booked.donationCents(), locale);
        }

        admin.note(
                "💶 Payment booked",
                "`" + booked.reference() + "` → <@" + payer + "> " + booked.days() + " days, until "
                        + AccessRoles.timestamp(booked.until()));
        log.info("Told {} of {}: {} days", payer, booked.reference(), booked.days());
    }

    /** Thanks a donation in public, in the channel of the donor's language; a plain purchase stays private. */
    private void announceDonation(final DiscordId discordId, final int donationCents, final Locale locale) {
        // An unconfigured language tag lands in the fallback channel.
        final String channelId = languages.forLocale(locale).contributionChannelId();
        // No contribution channel means no public thank-you; the donation is still booked.
        if (!Configured.isSet(channelId)) {
            return;
        }
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Contribution channel {} is not available; the thank-you was not posted", channelId);
            return;
        }
        channel.sendMessage(messages.format(
                        locale,
                        MESSAGES.publicSection()
                                .donation(
                                        new DiscordMemberContext("<@" + discordId + ">"), Money.format(donationCents))))
                .queue(ok -> {}, failure -> log.error("Could not post the donation thank-you", failure));
    }
}
