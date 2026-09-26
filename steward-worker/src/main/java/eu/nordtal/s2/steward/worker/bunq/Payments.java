package eu.nordtal.s2.steward.worker.bunq;

import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import eu.nordtal.s2.common.payment.Money;
import eu.nordtal.s2.common.payment.PaymentMatch;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.common.payment.PaymentRequests;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Everything that has to happen <em>at bunq</em>, driven by the {@code payment_request} table.
 *
 * <h2>The five things one pass does, in this order</h2>
 * <ol>
 *   <li><b>Expire.</b> An open request past its TTL is closed and its tab asked to go away. First,
 *       so the three steps below never work on a request that is already over.</li>
 *   <li><b>Create the tabs somebody asked for.</b> {@code tab_requested} without a
 *       {@code bunq_tab_id} - the row the bot writes instead of calling a bank from inside a Discord
 *       interaction.</li>
 *   <li><b>Cancel the tabs somebody asked to go away.</b> A closed request whose bunq.me URL still
 *       works is a link that can still be paid, against a reference nothing will book on its own.</li>
 *   <li><b>Match by tab.</b> A bunq.me tab knows which payments settled it: an exact link, no text
 *       parsing.</li>
 *   <li><b>Match by reference.</b> The fallback, for money that reached the account outside a tab -
 *       a bank transfer somebody typed by hand.</li>
 * </ol>
 *
 * <h2>What it does NOT do</h2>
 * It never books. Attribution and booking are two halves in two processes (concept §10d,
 * steward/109): this one writes {@code matched_cents} and {@code matched_by} onto a row that stays
 * {@code OPEN}, and {@code discord-bot} turns that into days, a role, a DM and a public thank-you -
 * none of which this container can do, because it has no Discord connection and no idea what a tier
 * is. {@code payment_request_settled_iff_paid} is never crossed here.
 *
 * <h2>Why the claim happens here and not at the booking</h2>
 * {@code recordMatch} writes {@code bunq_payment_id}, and the partial unique index on that column is
 * still the only thing in the system that prevents one payment from buying access twice. Claiming at
 * attribution rather than at booking means two requests can never both be told "this payment is
 * yours"; it also means {@code alreadyBooked} answers yes for a payment this pass has only just
 * found, which is what keeps the tab path and the reference path from finding the same money twice
 * inside one pass.
 */
@Slf4j
public final class Payments {

    /**
     * The reason string on a {@code payment_notice} for a payment no open request claims.
     * Not booked automatically, ever - see {@code /settle}.
     */
    private static final String UNMATCHED = "UNMATCHED";

    /** A payment that arrived on a reference belonging to a request that is no longer open. */
    private static final String EXPIRED_REFERENCE = "EXPIRED_REFERENCE";

    /**
     * Two requests claiming one payment. Unreachable unless something above is wrong, which is
     * exactly why it is reported rather than logged: it means money was attributed to nobody.
     */
    private static final String DOUBLE_CLAIM = "DOUBLE_CLAIM";

    /** How much of a failure message is worth putting in front of the person who is waiting. */
    private static final int REASON_LIMIT = 400;

    private final BunqGateway bunq;
    private final PaymentRequests requests;
    private final Instant watermark;
    private final int recentPaymentCount;

    /**
     * @param bunq               the bank
     * @param requests           the seam
     * @param watermark          payments created before it are ignored, completely and forever
     * @param recentPaymentCount how many recent payments the fallback scan reads per pass
     */
    public Payments(
            final BunqGateway bunq,
            final PaymentRequests requests,
            final Instant watermark,
            final int recentPaymentCount) {
        this.bunq = bunq;
        this.requests = requests;
        this.watermark = watermark;
        this.recentPaymentCount = recentPaymentCount;
    }

    /**
     * One pass. <b>Never throws</b>, for the same reason the bot's poll never did: a loop that dies
     * on one bad response from a bank is a deployment that silently stops noticing payments, and
     * nothing about it looks unhealthy.
     */
    public void pass() {
        step("the expiry sweep", this::expireOverdue);
        step("creating the tabs that were asked for", this::createTabs);
        step("cancelling the tabs that were asked to go", this::cancelTabs);
        step("matching payments against their tabs", this::matchByTab);
        step("matching payments against their reference", this::matchByReference);
    }

    /**
     * Each of the five is guarded separately rather than the pass as a whole. A bank that refuses
     * one call must not stop the cancel sweep from running, and a row that cannot be written must
     * not cost this pass its matching.
     */
    private void step(final String what, final Runnable work) {
        try {
            work.run();
        } catch (final RuntimeException failure) {
            log.error(
                    "The payment pass failed while {}; the rest of the pass continues and the"
                            + " next one starts from the table again",
                    what,
                    failure);
        }
    }

    // ---------------------------------------------------------------- expiry

    /**
     * Closes what is past its TTL and asks for its tab.
     *
     * <p>Both writes are one transaction, which is what {@code Purchases.close()} did with a
     * synchronous bunq call in front of it until steward/109. The bunq call itself happens in
     * {@link #cancelTabs()}, on the next step of this same pass.</p>
     */
    private void expireOverdue() {
        for (final PaymentRequest request : requests.dueForExpiry()) {
            if (requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.EXPIRED)) {
                log.info("Request {} expired unpaid", request.reference());
            }
        }
    }

    // ---------------------------------------------------------------- the two queues

    private void createTabs() {
        for (final PaymentRequest request : requests.tabsToCreate()) {
            final BunqGateway.Tab tab;
            try {
                tab = bunq.createTab(request.amountCents(), request.reference());
            } catch (final RuntimeException failure) {
                // The row leaves the queue carrying what bunq said. That sentence is what the
                // person staring at "your payment link is being created" is shown instead, and
                // without it the message never changes again - the exit steward/07 asked for.
                final String reason = reasonOf(failure);
                log.warn("bunq refused a tab for {}: {}", request.reference(), reason);
                requests.failTab(request.id(), reason);
                continue;
            }

            if (requests.attachTab(request.id(), tab.id(), tab.shareUrl())) {
                log.info("Created bunq.me tab {} for {}", tab.id(), request.reference());
                continue;
            }

            // The row closed between the queue read and now - an expiry sweep in another pass, or
            // the user starting a second purchase. The tab is live and nothing points at it, so it
            // is cancelled here rather than left payable.
            log.warn(
                    "Request {} closed while its tab was being created; cancelling tab {}",
                    request.reference(),
                    tab.id());
            bunq.cancelTab(tab.id());
        }
    }

    private void cancelTabs() {
        for (final PaymentRequest request : requests.tabsToCancel()) {
            final long tabId = request.tab().orElseThrow();
            final boolean accepted = bunq.cancelTab(tabId);

            // Recorded either way, and that is the decision rather than an oversight. cancelTab
            // answers false for a tab that is already cancelled or already paid - both of which
            // mean it can never be paid again, which is the whole point of asking. Leaving the row
            // in the queue on a false would make the worker cancel the same dead tab on every pass
            // until the season ends.
            if (requests.recordCancelled(request.id())) {
                log.info(
                        "Cancelled bunq.me tab {} for {}{}",
                        tabId,
                        request.reference(),
                        accepted ? "" : " (bunq had already closed it)");
            }
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
                attribute(request, payment.getId(), cents, PaymentMatch.TAB);
                break;
            }
        }
    }

    private void matchByReference() {
        for (final PaymentApiObject payment : bunq.recentPayments(recentPaymentCount)) {
            final Integer cents = eligible(payment);
            if (cents == null) {
                continue;
            }

            final String description = payment.getDescription() == null ? "" : payment.getDescription();
            final Matcher matcher = PaymentRequests.REFERENCE_PATTERN.matcher(description.toUpperCase(Locale.ROOT));
            if (!matcher.find()) {
                // Money that has nothing to do with this network - it shares an account with
                // whatever else lands there. Reporting every one of these would make the admin
                // channel unreadable, which is the same as not reporting anything.
                log.debug("Payment {} carries no NT- reference; ignoring it", payment.getId());
                continue;
            }

            final String reference = matcher.group();
            final Optional<PaymentRequest> request = requests.byReference(reference);
            if (request.isEmpty()) {
                requests.noticeOnce(
                        payment.getId(),
                        UNMATCHED,
                        "Payment " + payment.getId() + " (" + Money.format(cents) + ") carries reference `" + reference
                                + "`, which no request has.");
                continue;
            }
            if (request.get().status() != PaymentRequestStatus.OPEN) {
                requests.noticeOnce(
                        payment.getId(),
                        EXPIRED_REFERENCE,
                        "Payment " + payment.getId() + " (" + Money.format(cents) + ") arrived on `"
                                + reference + "`, which is " + request.get().status()
                                + ". Book it by hand with `/settle " + reference + "` if it is genuine.");
                continue;
            }
            attribute(request.get(), payment.getId(), cents, PaymentMatch.REFERENCE);
        }
    }

    /**
     * Writes the attribution, and turns the one exception it can raise into something a human hears
     * about.
     *
     * <p>{@code recordMatch} passes a unique violation on rather than swallowing it: this is the
     * single writer of {@code bunq_payment_id}, so a second claim is a bug here and not a race. The
     * money is real either way, so it goes to the admin channel as an unbookable payment rather than
     * into a log line.</p>
     */
    private void attribute(
            final PaymentRequest request, final long paymentId, final int cents, final PaymentMatch how) {
        try {
            if (requests.recordMatch(request.id(), paymentId, cents, how)) {
                log.info(
                        "Payment {} ({}) attributed to {} by {}",
                        paymentId,
                        Money.format(cents),
                        request.reference(),
                        how);
            }
        } catch (final UnableToExecuteStatementException clash) {
            log.error(
                    "Payment {} is already claimed by another request, so {} was not given it."
                            + " That should be impossible - it means two requests were matched to one"
                            + " payment.",
                    paymentId,
                    request.reference(),
                    clash);
            requests.noticeOnce(
                    paymentId,
                    DOUBLE_CLAIM,
                    "Payment " + paymentId + " (" + Money.format(cents) + ") was matched to `"
                            + request.reference() + "` but is already claimed by another request."
                            + " Nothing was granted for it; book it by hand if it is genuine.");
        }
    }

    /**
     * @return the amount in cents when this payment may be considered at all, {@code null}
     *         otherwise - not EUR, not positive, before the watermark, or already claimed by a row
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

    /**
     * What to write into {@code tab_failed}.
     *
     * <p>The person waiting sees this, so it is the message rather than the stack trace, bounded:
     * a bunq error can carry a whole JSON body, and a Discord message has a length.</p>
     */
    private static String reasonOf(final RuntimeException failure) {
        final String message =
                failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage().strip();
        return message.length() <= REASON_LIMIT ? message : message.substring(0, REASON_LIMIT) + "...";
    }
}
