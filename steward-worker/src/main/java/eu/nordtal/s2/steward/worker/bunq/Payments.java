package eu.nordtal.s2.steward.worker.bunq;

import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.inbox.BankRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.payment.Money;
import eu.nordtal.s2.database.payment.PaymentMatch;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.payment.PaymentRequestStatus;
import eu.nordtal.s2.database.payment.PaymentRequests;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jspecify.annotations.Nullable;

/**
 * Does everything that has to happen at bunq, driven by the bank's inbox and the {@code payment_request} table.
 * A pass expires, answers the bank's inbox (tabs to make, tabs to cancel), then matches by tab and by reference.
 */
@Slf4j
public final class Payments {

    /** The reason on a {@code payment_notice} for a payment no open request claims; never booked automatically. */
    private static final String UNMATCHED = "UNMATCHED";

    /** A payment that arrived on a reference belonging to a request that is no longer open. */
    private static final String EXPIRED_REFERENCE = "EXPIRED_REFERENCE";

    /** Two requests claiming one payment, which means money was attributed to nobody. */
    private static final String DOUBLE_CLAIM = "DOUBLE_CLAIM";

    /** How much of a failure message is worth putting in front of the person who is waiting. */
    private static final int REASON_LIMIT = 400;

    private final BunqGateway bunq;
    private final PaymentRequests requests;
    private final Inbox<BankRequest> inbox;
    private final Instant watermark;
    private final int recentPaymentCount;

    /**
     * Wires up one pass over the payment queue.
     *
     * @param bunq the bank
     * @param requests the seam
     * @param inbox the bank's inbox, whose one consumer this is
     * @param watermark payments created before it are ignored, completely and forever
     * @param recentPaymentCount how many recent payments the fallback scan reads per pass
     */
    public Payments(
            final BunqGateway bunq,
            final PaymentRequests requests,
            final Inbox<BankRequest> inbox,
            final Instant watermark,
            final int recentPaymentCount) {
        this.bunq = bunq;
        this.requests = requests;
        this.inbox = inbox;
        this.watermark = watermark;
        this.recentPaymentCount = recentPaymentCount;
    }

    /**
     * Runs one pass.
     *
     * Never throws: a loop that dies on one bad response silently stops noticing payments.
     */
    public void pass() {
        step("the expiry sweep", this::expireOverdue);
        step("answering the bank's inbox", () -> inbox.drain(this::answer));
        step("matching payments against their tabs", this::matchByTab);
        step("matching payments against their reference", this::matchByReference);
    }

    /** Guards one step of the pass, so one failing step does not cost the others. */
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

    /** Closes what is past its TTL and asks the bank's inbox to cancel its tab, which the next step does. */
    private void expireOverdue() {
        for (final PaymentRequest request : requests.dueForExpiry()) {
            if (requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.EXPIRED, Actor.STEWARD)) {
                log.info("Request {} expired unpaid", request.reference());
            }
        }
    }

    /** Makes or cancels one request's tab; a request that no longer needs it is done with nothing to do. */
    private Outcome answer(final Request<BankRequest> asked) {
        final Optional<PaymentRequest> found = requests.byId(asked.payload().payment());
        if (found.isEmpty()) {
            return Outcome.done(Map.of("nothing", "the payment request is gone"));
        }
        return switch (asked.payload()) {
            case BankRequest.OpenTab open -> createTab(found.get());
            case BankRequest.CancelTab cancel -> cancelTab(found.get());
        };
    }

    private Outcome createTab(final PaymentRequest request) {
        if (request.status() != PaymentRequestStatus.OPEN || request.tab().isPresent()) {
            return Outcome.done(Map.of("nothing", "the request is closed or has its tab"));
        }
        final BunqGateway.Tab tab;
        try {
            tab = bunq.createTab(request.amountCents(), request.reference());
        } catch (final RuntimeException failure) {
            // The payer is shown what bunq said, and pressing again is the retry.
            final String reason = reasonOf(failure);
            log.warn("bunq refused a tab for {}: {}", request.reference(), reason);
            requests.failTab(request.id(), reason);
            return Outcome.failed(Map.of("error", reason));
        }

        if (requests.attachTab(request.id(), tab.id(), tab.shareUrl())) {
            log.info("Created bunq.me tab {} for {}", tab.id(), request.reference());
            return Outcome.done(Map.of("tab", String.valueOf(tab.id())));
        }

        // The row closed since it was read, so its live, unclaimed tab is cancelled here.
        log.warn("Request {} closed while its tab was being created; cancelling tab {}", request.reference(), tab.id());
        bunq.cancelTab(tab.id());
        return Outcome.done(Map.of("cancelled", String.valueOf(tab.id())));
    }

    private Outcome cancelTab(final PaymentRequest request) {
        if (request.tab().isEmpty() || request.tabCancelled() != null) {
            return Outcome.done(Map.of("nothing", "there is no standing tab"));
        }
        final long tabId = request.tab().orElseThrow();
        final boolean accepted = bunq.cancelTab(tabId);

        // Recorded either way, since cancelTab returns false for an already-cancelled tab.
        if (requests.recordCancelled(request.id())) {
            log.info(
                    "Cancelled bunq.me tab {} for {}{}",
                    tabId,
                    request.reference(),
                    accepted ? "" : " (bunq had already closed it)");
        }
        return Outcome.done(Map.of("cancelled", String.valueOf(tabId)));
    }

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
                // Money unrelated to this network; reporting every one would flood the admin channel.
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

    /** Writes the attribution, and reports a second claim on a payment to the admin channel as unbookable. */
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
     * Returns the amount in cents when this payment may be considered at all.
     *
     * @return the cents, or {@code null} when not EUR, not positive, before the watermark or already claimed
     */
    private @Nullable Integer eligible(final PaymentApiObject payment) {
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

    /** Returns what to write into {@code tab_failed}: the message, bounded, since the person waiting sees it. */
    private static String reasonOf(final RuntimeException failure) {
        final String message =
                failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage().strip();
        return message.length() <= REASON_LIMIT ? message : message.substring(0, REASON_LIMIT) + "...";
    }
}
