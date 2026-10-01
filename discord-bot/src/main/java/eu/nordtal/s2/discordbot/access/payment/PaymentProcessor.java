package eu.nordtal.s2.discordbot.access.payment;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AccessSource;
import eu.nordtal.s2.database.payment.Money;
import eu.nordtal.s2.database.payment.PaymentNotice;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.DiscordMemberContext;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Books the payments steward has matched and posts the notices that need a human.
 *
 * Driven by {@code nordtal_payment}, with the timer in {@code AccessBot} as the guarantee.
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

    /** Runs one pass; never throws, since a loop that dies on one bad row stops booking payments. */
    public void poll() {
        try {
            bookWhatWasMatched();
            postWhatNeedsAHuman();
        } catch (final RuntimeException exception) {
            log.error("The payment pass failed", exception);
        }
    }

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
     * @param request   the open request steward attributed money to
     * @param paymentId the bunq payment
     * @param cents     what actually arrived, not what the request asked for
     */
    private void book(final PaymentRequest request, final long paymentId, final int cents) {
        // The order first, the amount second: honoured when the money covers it, re-derived only when short.
        final Optional<Tiers.Settlement> resolved = tiers.resolve(cents, Tiers.Order.of(request));
        if (resolved.isEmpty()) {
            // Leaves the request open: the money is real but not enough, which is a human decision.
            raise(
                    paymentId,
                    "BELOW_MINIMUM",
                    "<@" + request.discordId() + "> paid " + Money.format(cents) + " on `" + request.reference()
                            + "`, below the cheapest tier. Nothing was granted.");
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
        // "Downgraded" means the payer edited the amount down, which the DM says plainly.
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
                request.discordId().value(),
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

    /** Posts what steward found and could not act on, claiming each row before it is sent. */
    private void postWhatNeedsAHuman() {
        for (final PaymentNotice notice : requests.unpostedNotices()) {
            if (requests.claimNotice(notice.bunqPaymentId())) {
                // Falls back to the reason label on a notice built with no detail sentence.
                admin.alert("💶 Payment needs a look", Objects.requireNonNullElse(notice.detail(), notice.reason()));
            }
        }
    }

    /** Raises a payment to the admin channel once ever, writing and claiming the notice together. */
    private void raise(final long paymentId, final String reason, final String text) {
        if (requests.noticeOnce(paymentId, reason, text) && requests.claimNotice(paymentId)) {
            admin.alert("💶 Payment needs a look", text);
        }
    }
}
