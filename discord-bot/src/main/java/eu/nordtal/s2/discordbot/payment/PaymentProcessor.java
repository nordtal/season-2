package eu.nordtal.s2.discordbot.payment;

import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import eu.nordtal.s2.discordbot.bunq.BunqGateway;
import eu.nordtal.s2.discordbot.bunq.Money;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.discord.AccessRoles;
import eu.nordtal.s2.discordbot.discord.AdminLog;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Turns money that arrived at bunq into access.
 *
 * <h2>Two paths, one gate</h2>
 * <ol>
 *   <li><b>The tab.</b> A bunq.me tab knows which payments settled it, so for every open request
 *       the bot asks its own tab. That is an exact link and needs no text parsing.</li>
 *   <li><b>The reference.</b> Recent payments are scanned for {@code NT-XXXXXX}. This only exists
 *       for money that reached the account outside a tab.</li>
 * </ol>
 * Both are gated by the configured watermark. Without it the first run against an empty database
 * books up to fifty historical payments, with the grants, roles, DMs and public thank-yous that go
 * with them.
 *
 * <h2>What is never done automatically</h2>
 * A payment against a reference that is not open is <b>not</b> booked - not superseded, not
 * expired, not already paid. It goes to the admin channel with {@code /settle} as the way to book
 * it by hand, because the alternative is the bot handing out access for a tab it had already
 * cancelled.
 */
@Slf4j
public final class PaymentProcessor {

    private final AccessSpec config;
    private final BunqGateway bunq;
    private final PaymentRequests requests;
    private final Purchases purchases;
    private final Tiers tiers;
    private final AccessDirectory access;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final Messages messages;
    private final JDA jda;
    private final Instant watermark;

    public PaymentProcessor(final AccessSpec config, final BunqGateway bunq, final PaymentRequests requests,
                            final Purchases purchases, final Tiers tiers, final AccessDirectory access,
                            final AccessRoles roles, final AdminLog admin, final Messages messages,
                            final JDA jda, final Instant watermark) {
        this.config = config;
        this.bunq = bunq;
        this.requests = requests;
        this.purchases = purchases;
        this.tiers = tiers;
        this.access = access;
        this.roles = roles;
        this.admin = admin;
        this.messages = messages;
        this.jda = jda;
        this.watermark = watermark;
    }

    /** One poll. Never throws: a poll loop that dies on one bad response stops booking payments. */
    public void poll() {
        try {
            expireOverdueRequests();
            matchByTab();
            matchByReference();
        } catch (final RuntimeException exception) {
            log.error("The payment poll failed", exception);
        }
    }

    // ---------------------------------------------------------------- expiry

    private void expireOverdueRequests() {
        for (final PaymentRequest request : requests.dueForExpiry()) {
            purchases.close(request, PaymentRequestStatus.EXPIRED);
        }
    }

    // ---------------------------------------------------------------- matching

    private void matchByTab() {
        for (final PaymentRequest request : requests.openWithTab()) {
            final long tabId = request.tab().orElseThrow();
            for (final PaymentApiObject payment : bunq.paymentsFor(tabId)) {
                final Integer cents = eligible(payment);
                if (cents == null) {
                    continue;
                }
                settle(request, payment.getId(), cents);
                break;
            }
        }
    }

    private void matchByReference() {
        for (final PaymentApiObject payment : bunq.recentPayments(config.payment().recentPaymentCount())) {
            final Integer cents = eligible(payment);
            if (cents == null) {
                continue;
            }

            final String description = payment.getDescription() == null ? "" : payment.getDescription();
            final Matcher matcher = PaymentRequests.REFERENCE_PATTERN.matcher(description.toUpperCase(Locale.ROOT));
            if (!matcher.find()) {
                // Money that has nothing to do with the bot - it shares an account with whatever
                // else lands there. Reporting every one of these would make the admin channel
                // unreadable, which is the same as not reporting anything.
                log.debug("Payment {} carries no NT- reference; ignoring it", payment.getId());
                continue;
            }

            final String reference = matcher.group();
            final Optional<PaymentRequest> request = requests.byReference(reference);
            if (request.isEmpty()) {
                raise(payment.getId(), "UNMATCHED",
                        "Payment " + payment.getId() + " (" + Money.format(cents) + ") carries reference `"
                                + reference + "`, which no request has.");
                continue;
            }
            if (request.get().status() != PaymentRequestStatus.OPEN) {
                raise(payment.getId(), "EXPIRED_REFERENCE",
                        "Payment " + payment.getId() + " (" + Money.format(cents) + ") arrived on `"
                                + reference + "`, which is " + request.get().status()
                                + ". Book it by hand with `/settle " + reference + "` if it is genuine.");
                continue;
            }
            settle(request.get(), payment.getId(), cents);
        }
    }

    /**
     * @return the amount in cents when this payment may be considered at all, {@code null}
     *         otherwise - not EUR, not positive, before the watermark, or already booked
     */
    private Integer eligible(final PaymentApiObject payment) {
        if (payment.getId() == null) {
            return null;
        }
        final Instant created = BunqGateway.createdAt(payment);
        if (created == null || created.isBefore(watermark)) {
            return null;
        }
        if (requests.alreadyBooked(payment.getId())) {
            return null;
        }
        return BunqGateway.positiveEuroCents(payment);
    }

    // ---------------------------------------------------------------- booking

    /**
     * Books one payment against one request, applying the "pay what you get" rule.
     *
     * @param request the open request
     * @param paymentId the bunq payment
     * @param cents   what actually arrived - not what the request asked for
     */
    public void settle(final PaymentRequest request, final long paymentId, final int cents) {
        // The order first, the amount second. What the row records is what the payer asked for,
        // and it is honoured whenever the money covers it - the tiers are only re-derived when the
        // payment falls short of the order.
        final Optional<Tiers.Settlement> resolved = tiers.resolve(cents, Tiers.Order.of(request));
        if (resolved.isEmpty()) {
            // Deliberately leaves the request open: the money is real, it is simply not enough for
            // anything, and what to do about that is a decision for a human.
            raise(paymentId, "BELOW_MINIMUM",
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
        final AccessGrant grant = access.grantAccess(
                request.discordId(), settlement.days(), AccessSource.PURCHASE, request.id());
        final Locale locale = roles.localeOf(request.discordId());

        if (settlement.donation()) {
            access.setDonor(request.discordId(), true);
            roles.grantDonorRole(request.discordId());
        }
        roles.applyAccessRole(request.discordId(), true);

        // "Downgraded" means the payer edited the amount down on the bunq.me page. Saying so is
        // the difference between a confusing purchase and an obvious one.
        roles.dm(request.discordId(), settlement.downgraded()
                ? messages.format(locale, "dm.granted.short",
                "paid", Money.format(cents),
                "days", settlement.days(),
                "until", AccessRoles.timestamp(grant.validUntil()))
                : messages.format(locale, "dm.granted", "until", AccessRoles.timestamp(grant.validUntil())));

        if (settlement.donation()) {
            roles.dm(request.discordId(), messages.get(locale, "dm.donor"));
            announceDonation(request.discordId(), settlement.donationCents(), locale);
        }

        admin.record("SETTLE", null, request.discordId(), null,
                "reference=" + request.reference() + " payment=" + paymentId
                        + " received=" + cents + "c ordered=" + request.days() + "d"
                        + " granted=" + settlement.days() + "d"
                        + (settlement.donation() ? " donation=" + settlement.donationCents() + "c" : "")
                        + (settlement.downgraded() ? " DOWNGRADED" : ""));
        log.info("Booked {} on {} - {} days for {}", paymentId, request.reference(), settlement.days(),
                request.discordId());
    }

    /**
     * The one public message the bot writes. A plain access purchase stays private - somebody
     * buying the right to play is not an announcement - while a donation is thanked in the open,
     * in the channel of the donor's own language.
     */
    private void announceDonation(final String discordId, final int donationCents, final Locale locale) {
        final String channelId = "de".equals(locale.getLanguage())
                ? config.channels().contributionDe()
                : config.channels().contributionEn();
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            log.error("Contribution channel {} is not available; the thank-you was not posted", channelId);
            return;
        }
        channel.sendMessage(messages.format(locale, "public.donation",
                        "user", "<@" + discordId + ">",
                        "amount", Money.format(donationCents)))
                .queue(ok -> {
                }, failure -> log.error("Could not post the donation thank-you", failure));
    }

    /** Raises a payment to the admin channel, once ever - see {@code payment_notice}. */
    private void raise(final long paymentId, final String reason, final String text) {
        if (requests.noticeOnce(paymentId, reason, text)) {
            admin.alert(text);
        }
    }
}
