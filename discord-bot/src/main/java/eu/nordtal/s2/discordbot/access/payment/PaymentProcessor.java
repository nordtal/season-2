package eu.nordtal.s2.discordbot.access.payment;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;
import eu.nordtal.s2.common.payment.Money;
import eu.nordtal.s2.common.payment.PaymentNotice;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.discord.AdminLog;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Turns money steward-worker has <em>found</em> into access.
 *
 * <h2>This used to be both halves, and since steward/109 it is one</h2>
 * It asked bunq about every open tab, scanned recent payments for a {@code NT-XXXXXX} reference,
 * decided which request the money belonged to, and then granted the days. The first three of those
 * needed a bunq API key inside the Discord process; the last one needs Discord and nothing else. So
 * the finding moved to {@code steward-worker} - the only process that now holds a bank credential -
 * and what is left here is everything that has to happen in a guild:
 *
 * <ol>
 *   <li><b>Book what was matched.</b> A row that is still {@code OPEN} and carries
 *       {@code matched_cents} is money that arrived and nobody has been given anything for. The
 *       tier is derived from what actually arrived, the grant is written, the role is set, the DM
 *       is sent, a donation is thanked for in public, and the audit entry is recorded.</li>
 *   <li><b>Say what needs a human.</b> {@code payment_notice} rows the worker wrote - an unmatchable
 *       payment, a payment on a reference that is no longer open - posted to the admin channel
 *       exactly once each.</li>
 * </ol>
 *
 * <h2>What is still never done automatically</h2>
 * A payment against a reference that is not open is <b>not</b> booked - not superseded, not expired,
 * not already paid. It arrives here as a notice with {@code /settle} named in it, because the
 * alternative is handing out access for a tab that had already been cancelled.
 *
 * <h2>How it is driven</h2>
 * By {@code nordtal_payment}, with the timer in {@code AccessBot} as the guarantee underneath it -
 * the same arrangement as everywhere else in this network. {@link #poll()} is safe to call from
 * either, and re-reads both queues in full every time.
 */
@Slf4j
public final class PaymentProcessor {

    private final Languages languages;
    private final PaymentRequests requests;
    private final Tiers tiers;
    private final AccessDirectory access;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final Messages messages;
    private final JDA jda;

    private final SeasonStart seasonStart;

    public PaymentProcessor(
            final Languages languages,
            final PaymentRequests requests,
            final Tiers tiers,
            final AccessDirectory access,
            final AccessRoles roles,
            final AdminLog admin,
            final Messages messages,
            final JDA jda,
            final SeasonStart seasonStart) {
        this.languages = languages;
        this.requests = requests;
        this.tiers = tiers;
        this.access = access;
        this.roles = roles;
        this.admin = admin;
        this.messages = messages;
        this.jda = jda;
        this.seasonStart = seasonStart;
    }

    /** One pass. Never throws: a loop that dies on one bad row stops booking payments. */
    public void poll() {
        try {
            bookWhatWasMatched();
            postWhatNeedsAHuman();
        } catch (final RuntimeException exception) {
            log.error("The payment pass failed", exception);
        }
    }

    // ---------------------------------------------------------------- booking

    private void bookWhatWasMatched() {
        for (final PaymentRequest request : requests.matchedAwaitingBooking()) {
            // Both are non-null by the queue's own predicate: matched_cents is what it selects on,
            // and recordMatch is the only statement that writes it - in the same UPDATE that claims
            // bunq_payment_id.
            book(request, request.bunqPaymentId(), request.matchedCents());
        }
    }

    /**
     * Books one payment against one request, applying the "pay what you get" rule.
     *
     * @param request the open request steward-worker attributed money to
     * @param paymentId the bunq payment
     * @param cents   what actually arrived - not what the request asked for
     */
    private void book(final PaymentRequest request, final long paymentId, final int cents) {
        // The order first, the amount second. What the row records is what the payer asked for,
        // and it is honoured whenever the money covers it - the tiers are only re-derived when the
        // payment falls short of the order.
        final Optional<Tiers.Settlement> resolved = tiers.resolve(cents, Tiers.Order.of(request));
        if (resolved.isEmpty()) {
            // Deliberately leaves the request open: the money is real, it is simply not enough for
            // anything, and what to do about that is a decision for a human. It stays out of the
            // booking queue on the next pass only because the notice is written once - so the
            // sentence below is worded as a state, not as an event.
            raise(
                    paymentId,
                    "BELOW_MINIMUM",
                    "Payment " + paymentId + " on `" + request.reference() + "` from <@" + request.discordId()
                            + "> is " + Money.format(cents) + ", which is below the cheapest tier. "
                            + "Nothing was granted.");
            return;
        }

        if (!requests.settle(request.id(), paymentId)) {
            log.info("Request {} was already closed when payment {} was booked", request.reference(), paymentId);
            return;
        }

        final Tiers.Settlement settlement = resolved.get();
        final AccessGrant grant =
                access.grantAccess(request.discordId(), settlement.days(), AccessSource.PURCHASE, request.id());
        seasonStart.warnIfUnanchored(request.discordId(), grant);
        final Locale locale = roles.localeOf(request.discordId());

        if (settlement.donation()) {
            access.setDonor(request.discordId(), true);
            roles.grantDonorRole(request.discordId());
        }
        roles.applyAccessRole(request.discordId(), true);

        // "Downgraded" means the payer edited the amount down on the bunq.me page. Saying so is
        // the difference between a confusing purchase and an obvious one.
        roles.dm(
                request.discordId(),
                settlement.downgraded()
                        ? messages.format(
                                locale,
                                MESSAGES.dm()
                                        .grantedSection()
                                        .shortMessage(
                                                Money.format(cents),
                                                settlement.days(),
                                                AccessRoles.timestamp(grant.validUntil())))
                        : messages.format(locale, MESSAGES.dm().granted(AccessRoles.timestamp(grant.validUntil()))));

        if (settlement.donation()) {
            roles.dm(request.discordId(), messages.format(locale, MESSAGES.dm().donor()));
            announceDonation(request.discordId(), settlement.donationCents(), locale);
        }

        admin.record(
                "SETTLE",
                null,
                request.discordId(),
                null,
                "reference=" + request.reference() + " payment=" + paymentId
                        + " matched=" + request.matchedBy()
                        + " received=" + cents + "c ordered=" + request.days() + "d"
                        + " granted=" + settlement.days() + "d"
                        + (settlement.donation() ? " donation=" + settlement.donationCents() + "c" : "")
                        + (settlement.downgraded() ? " DOWNGRADED" : ""));
        log.info(
                "Booked {} on {} - {} days for {}",
                paymentId,
                request.reference(),
                settlement.days(),
                request.discordId());
    }

    /**
     * The one public message the bot writes. A plain access purchase stays private - somebody
     * buying the right to play is not an announcement - while a donation is thanked in the open,
     * in the channel of the donor's own language.
     */
    private void announceDonation(final String discordId, final int donationCents, final Locale locale) {
        // A language that is not configured - a tag left in discord_user.locale by an entry since
        // removed from access.yml - lands in the fallback channel rather than nowhere.
        final String channelId = languages.forLocale(locale).contributionChannelId();
        // No contribution channel configured means no public thank-you. The donation itself is
        // booked, the donor flag is set and the role is given; only the applause is missing.
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

    // ---------------------------------------------------------------- the admin channel

    /**
     * Posts what steward-worker found and could not act on.
     *
     * <p>The row is claimed before the message is sent, and that order is the same one
     * {@code noticeOnce} always had: Discord can accept a message and this process can then die, so
     * the choice is between saying it twice and not saying it at all. Twice is noise; not at all is
     * money nobody hears about.</p>
     */
    private void postWhatNeedsAHuman() {
        for (final PaymentNotice notice : requests.unpostedNotices()) {
            if (requests.claimNotice(notice.bunqPaymentId())) {
                admin.alert(notice.detail());
            }
        }
    }

    /**
     * Raises a payment to the admin channel, once ever.
     *
     * <p>Written and claimed in one breath here, because this process both found the problem and can
     * say so - unlike the worker's notices, which travel through the table. The claim is what stops
     * the next pass from repeating it: the row stays, the message does not.</p>
     */
    private void raise(final long paymentId, final String reason, final String text) {
        if (requests.noticeOnce(paymentId, reason, text) && requests.claimNotice(paymentId)) {
            admin.alert(text);
        }
    }
}
