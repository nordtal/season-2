package eu.nordtal.season.discordbot.access.payment;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.access.SeasonStart;
import eu.nordtal.season.discordbot.access.discord.AccessRoles;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordbot.config.Languages;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.Money;
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
    private final DiscordRenderer messages;
    private final JDA jda;
    private final SeasonStart seasonStart;

    public BookingReaction(
            final Languages languages,
            final AccessRoles roles,
            final AdminLog admin,
            final DiscordRenderer messages,
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
                                                Money.euroCents(Objects.requireNonNull(
                                                        booked.receivedCents(), "a short payment has an amount")),
                                                booked.days(),
                                                booked.until()))
                        : messages.format(locale, MESSAGES.dm().granted(booked.until())));
        if (booked.donation()) {
            roles.dm(payer, messages.format(locale, MESSAGES.dm().donor()));
            announceDonation(payer, booked.donationCents(), locale);
        }

        admin.note(
                "💶",
                TEXTS.note().paymentBooked(),
                TEXTS.note().booked(booked.reference(), Mention.of(payer), booked.days(), booked.until()));
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
                                        // Discord shows the mention; the id stands in for a name only elsewhere.
                                        new DiscordMemberContext(discordId, discordId.value()),
                                        Money.euroCents(donationCents))))
                .queue(ok -> {}, failure -> log.error("Could not post the donation thank-you", failure));
    }
}
