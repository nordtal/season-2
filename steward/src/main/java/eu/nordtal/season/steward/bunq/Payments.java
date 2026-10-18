package eu.nordtal.season.steward.bunq;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.inbox.BankRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.payment.Bookings;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.Money;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/** Runs the payment handling. A pass expires requests, answers the bank inbox, matches payments by tab and by reference and books them through {@link Bookings}. */
@Slf4j
public final class Payments {

    private static final String RAISED_BY = "steward";

    private final Bank bunq;
    private final PaymentRequests requests;
    private final Bookings bookings;
    private final Tiers tiers;
    private final AlertBook alerts;
    private final Inbox<BankRequest> inbox;
    private final Instant watermark;
    private final int recentPaymentCount;

    /** @param watermark payments created before it are ignored */
    public Payments(
            final Bank bunq,
            final PaymentRequests requests,
            final Bookings bookings,
            final Tiers tiers,
            final AlertBook alerts,
            final Inbox<BankRequest> inbox,
            final Instant watermark,
            final int recentPaymentCount) {
        this.bunq = bunq;
        this.requests = requests;
        this.bookings = bookings;
        this.tiers = tiers;
        this.alerts = alerts;
        this.inbox = inbox;
        this.watermark = watermark;
        this.recentPaymentCount = recentPaymentCount;
    }

    public void pass() {
        step("the expiry sweep", this::expireOverdue);
        step("answering the bank's inbox", () -> inbox.drain(this::answer));
        step("matching payments against their tabs", this::matchByTab);
        step("matching payments against their reference", this::matchByReference);
    }

    /** Logs a failure of the step, so the other steps still run. */
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

    private void expireOverdue() {
        for (final PaymentRequest request : requests.dueForExpiry()) {
            if (requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.EXPIRED, Actor.STEWARD)) {
                log.info("Request {} expired unpaid", request.reference());
            }
        }
    }

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
        final BankWire.Tab tab;
        try {
            tab = bunq.createTab(request.amountCents(), request.reference());
        } catch (final Bank.TabRefused refused) {
            // The payer sees a text for the refusal code. The words of bunq stay in this log.
            log.warn(
                    "bunq refused a tab for {} ({}): {}", request.reference(), refused.refusal(), refused.getMessage());
            requests.failTab(request.id(), refused.refusal());
            return Outcome.failed(Map.of("refusal", refused.refusal().name()));
        }

        if (requests.attachTab(request.id(), tab.id(), tab.shareUrl())) {
            log.info("Created bunq.me tab {} for {}", tab.id(), request.reference());
            return Outcome.done(Map.of("tab", String.valueOf(tab.id())));
        }

        // The request closed after it was read, so the new tab is cancelled.
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

        // cancelTab returns false for an already cancelled tab, so the cancel is recorded anyway.
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
            for (final BankWire.Payment payment : bunq.paymentsFor(tabId)) {
                if (eligible(payment)) {
                    attribute(request, payment.id(), payment.cents(), PaymentMatch.TAB);
                    break;
                }
            }
        }
    }

    private void matchByReference() {
        for (final BankWire.Payment payment : bunq.recentPayments(recentPaymentCount)) {
            if (!eligible(payment)) {
                continue;
            }
            final long paymentId = payment.id();
            final int cents = payment.cents();

            final Matcher matcher = PaymentRequests.REFERENCE_PATTERN.matcher(
                    payment.description().toUpperCase(Locale.ROOT));
            if (!matcher.find()) {
                log.debug("Payment {} carries no NT- reference; ignoring it", paymentId);
                continue;
            }

            final String reference = matcher.group();
            final Optional<PaymentRequest> request = requests.byReference(reference);
            if (request.isEmpty()) {
                needsALook(
                        paymentId,
                        TEXTS.alert().unknownReference(String.valueOf(paymentId), Money.euroCents(cents), reference));
                continue;
            }
            if (request.get().status() != PaymentRequestStatus.OPEN) {
                needsALook(
                        paymentId,
                        TEXTS.alert()
                                .notOpen(
                                        String.valueOf(paymentId),
                                        Money.euroCents(cents),
                                        reference,
                                        request.get().status()));
                continue;
            }
            attribute(request.get(), paymentId, cents, PaymentMatch.REFERENCE);
        }
    }

    private void attribute(
            final PaymentRequest request, final long paymentId, final int cents, final PaymentMatch how) {
        final Bookings.Booking booking;
        try {
            booking = bookings.book(
                    request.id(),
                    Bookings.Arrival.paid(paymentId, cents, how),
                    order -> tiers.resolve(cents, order),
                    Actor.STEWARD);
        } catch (final UnableToExecuteStatementException clash) {
            log.error(
                    "Payment {} is already claimed by another request, so {} was not given it."
                            + " That should be impossible - it means two requests were matched to one"
                            + " payment.",
                    paymentId,
                    request.reference(),
                    clash);
            needsALook(
                    paymentId,
                    TEXTS.alert().claimed(String.valueOf(paymentId), Money.euroCents(cents), request.reference()));
            return;
        }
        switch (booking) {
            case Bookings.Booking.Booked booked ->
                log.info(
                        "Payment {} ({} cents) booked to {} by {}: {} days",
                        paymentId,
                        cents,
                        request.reference(),
                        how,
                        booked.told().days());
            // The request closed after it was read.
            case Bookings.Booking.NotOpen closed -> {}
            case Bookings.Booking.BelowMinimum below ->
                needsALook(
                        paymentId,
                        TEXTS.alert().noTier(String.valueOf(paymentId), Money.euroCents(cents), request.reference()));
        }
    }

    /** Raises an alert once per bank payment. */
    private void needsALook(final long paymentId, final MessageRef detail) {
        if (alerts.raiseOnce(
                "payment:" + paymentId,
                new Alert(
                        AlertType.PAYMENT,
                        Alert.Level.DOWN,
                        "payment",
                        TEXTS.alert().payment(),
                        List.of(detail),
                        "/payments"),
                RAISED_BY)) {
            log.warn("Payment {} needs a look: {}", paymentId, detail);
        }
    }

    private boolean eligible(final BankWire.Payment payment) {
        return !Instant.parse(payment.created()).isBefore(watermark) && !requests.alreadyBooked(payment.id());
    }
}
