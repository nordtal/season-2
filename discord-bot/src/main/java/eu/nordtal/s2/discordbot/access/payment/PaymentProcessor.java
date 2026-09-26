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
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Turns money steward-worker has found into access.
 *
 * Finding a payment needs a bunq API key; booking it needs only Discord. The finding lives in {@code steward-worker}
 * - the only process that holds a bank credential - and what is left here is everything that has to happen in a
 * guild: booking what was matched, and saying what needs a human.
 *
 * Booking: a row that is still {@code OPEN} and carries {@code matched_cents} is money that arrived and nobody has
 * been given anything for. The tier is derived from what actually arrived, the grant is written, the role is set,
 * the DM is sent, a donation is thanked for in public, and the audit entry is recorded.
 *
 * Needs a human: {@code payment_notice} rows the worker wrote - an unmatchable payment, a payment on a reference
 * that is no longer open - posted to the admin channel exactly once each.
 *
 * A payment against a reference that is not open is not booked - not superseded, not expired, not already paid. It
 * arrives here as a notice with {@code /settle} named in it, because the alternative is handing out access for a tab
 * that had already been cancelled.
 *
 * This is driven by {@code nordtal_payment}, with the timer in {@code AccessBot} as the guarantee underneath it.
 * {@link #poll()} is safe to call from either, and re-reads both queues in full every time.
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

    // Booking.

    private void bookWhatWasMatched() {
        for (final PaymentRequest request : requests.matchedAwaitingBooking()) {
            // Both non-null by the queue's predicate: recordMatch writes both in the one UPDATE claiming the id.
            book(
                    request,
                    Objects.requireNonNull(request.bunqPaymentId()),
                    Objects.requireNonNull(request.matchedCents()));
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
        // The order first, the amount second: honoured when the money covers it, re-derived only when it falls short.
        final Optional<Tiers.Settlement> resolved = tiers.resolve(cents, Tiers.Order.of(request));
        if (resolved.isEmpty()) {
            // Leaves the request open: the money is real but not enough, and what to do about it is a human decision.
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
        notify(request, cents, settlement, grant, locale);
        recordBooking(request, paymentId, cents, settlement);
    }

    private void notify(
            final PaymentRequest request,
            final int cents,
            final Tiers.Settlement settlement,
            final AccessGrant grant,
            final Locale locale) {
        // "Downgraded" means the payer edited the amount down - a confusing purchase, said plainly, is an obvious one.
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
    }

    private void recordBooking(
            final PaymentRequest request, final long paymentId, final int cents, final Tiers.Settlement settlement) {
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
     * The one public message the bot writes.
     *
     * A plain access purchase stays private - somebody buying the right to play is not an announcement - while a
     * donation is thanked in the open, in the channel of the donor's own language.
     */
    private void announceDonation(final String discordId, final int donationCents, final Locale locale) {
        // An unconfigured language - a tag left behind by a removed access.yml entry - lands in the fallback channel.
        final String channelId = languages.forLocale(locale).contributionChannelId();
        // No contribution channel means no public thank-you; the donation is still booked, flagged and given the role.
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

    // The admin channel.

    /**
     * Posts what steward-worker found and could not act on.
     *
     * The row is claimed before the message is sent, and that order is the same one {@code noticeOnce} always had:
     * Discord can accept a message and this process can then die, so the choice is between saying it twice and not
     * saying it at all. Twice is noise; not at all is money nobody hears about.
     */
    private void postWhatNeedsAHuman() {
        for (final PaymentNotice notice : requests.unpostedNotices()) {
            if (requests.claimNotice(notice.bunqPaymentId())) {
                // Falls back to the reason label on the rare notice built with no detail sentence.
                admin.alert(Objects.requireNonNullElse(notice.detail(), notice.reason()));
            }
        }
    }

    /**
     * Raises a payment to the admin channel, once ever.
     *
     * Written and claimed in one breath here, because this process both found the problem and can say so - unlike the
     * worker's notices, which travel through the table. The claim is what stops the next pass from repeating it: the
     * row
     * stays, the message does not.
     */
    private void raise(final long paymentId, final String reason, final String text) {
        if (requests.noticeOnce(paymentId, reason, text) && requests.claimNotice(paymentId)) {
            admin.alert(text);
        }
    }
}
