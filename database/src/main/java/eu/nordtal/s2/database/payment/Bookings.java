package eu.nordtal.s2.database.payment;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AccessSource;
import eu.nordtal.s2.database.access.Grants;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.Journal;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Books a payment in one transaction, which all of it commits in or none of it does.
 * The request is paid, the access appended, the donor flag set, the journal line written and the bot told.
 * steward is the one caller, for money its poll matched and for an admin's booking by hand alike.
 */
public final class Bookings {

    /** The journal's action for a booking, by the poll or by hand. */
    public static final String ACTION = "SETTLE";

    private final Jdbi jdbi;
    private final Inbox<BotRequest> bot;

    /** Borrows the pool it is given and owns nothing; the process that built it closes it. */
    public Bookings(final DataSource dataSource) {
        this.jdbi = Jdbis.over(dataSource);
        this.bot = Inbox.over(dataSource, BotRequest.TABLE);
    }

    /**
     * What arrived for a request, and how it was found.
     *
     * @param matchedBy     the tab, the reference, or an admin's word
     * @param bunqPaymentId the bank's payment, {@code null} for a booking by hand
     * @param receivedCents what it was worth, {@code null} for a booking by hand
     */
    public record Arrival(
            PaymentMatch matchedBy,
            @Nullable Long bunqPaymentId,
            @Nullable Integer receivedCents) {

        public Arrival {
            Objects.requireNonNull(matchedBy, "matchedBy");
            if ((matchedBy == PaymentMatch.MANUAL) != (bunqPaymentId == null)
                    || (bunqPaymentId == null) != (receivedCents == null)) {
                throw new IllegalArgumentException(
                        "a bank payment carries its id and amount, and a booking by hand neither");
            }
        }

        /** Money the poll found at the bank. */
        public static Arrival paid(final long bunqPaymentId, final int receivedCents, final PaymentMatch matchedBy) {
            return new Arrival(matchedBy, bunqPaymentId, receivedCents);
        }

        /** An admin's word that the money arrived, with no bank payment behind it. */
        public static Arrival byHand() {
            return new Arrival(PaymentMatch.MANUAL, null, null);
        }
    }

    /** How one booking ended. */
    public sealed interface Booking {

        /** Booked and committed; the bot is told with exactly this. */
        record Booked(BotRequest.PaymentBooked told) implements Booking {}

        /** The request was no longer open, so nothing was written. */
        record NotOpen() implements Booking {}

        /** The money does not cover the cheapest tier, which is a human's decision; nothing was written. */
        record BelowMinimum(PaymentRequest request) implements Booking {}
    }

    /**
     * Books what arrived for one request.
     *
     * @param request the request the money belongs to
     * @param arrival what arrived and how it was found
     * @param rule    what the order buys with that money, read off the locked row; empty when it buys nothing
     * @param by      who booked it: steward for the poll, the admin for a booking by hand
     * @throws org.jdbi.v3.core.statement.UnableToExecuteStatementException when another request already booked this
     *     bank payment, which rolls everything back
     */
    public Booking book(
            final UUID request,
            final Arrival arrival,
            final Function<Tiers.Order, Optional<Tiers.Settlement>> rule,
            final Actor by) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(by, "by");
        return jdbi.inTransaction(handle -> {
            final PaymentRequestDao dao = handle.attach(PaymentRequestDao.class);
            final Optional<PaymentRequest> locked = dao.lockOpen(request);
            if (locked.isEmpty()) {
                return new Booking.NotOpen();
            }
            final PaymentRequest open = locked.get();
            final Optional<Tiers.Settlement> settled = rule.apply(Tiers.Order.of(open));
            if (settled.isEmpty()) {
                return new Booking.BelowMinimum(open);
            }
            final Tiers.Settlement settlement = settled.get();

            dao.markPaid(
                    open.id(),
                    arrival.bunqPaymentId(),
                    arrival.receivedCents(),
                    arrival.matchedBy().name());
            final AccessGrant grant =
                    Grants.append(handle, open.discordId(), settlement.days(), AccessSource.PURCHASE, open.id());
            if (settlement.donation()) {
                Grants.markDonor(handle, open.discordId());
            }
            final BotRequest.PaymentBooked told = new BotRequest.PaymentBooked(
                    open.id(),
                    open.discordId(),
                    open.reference(),
                    settlement.days(),
                    settlement.donationCents(),
                    settlement.downgraded(),
                    arrival.receivedCents(),
                    grant.validFrom(),
                    grant.validUntil());
            Journal.write(handle, journalLine(open, arrival, settlement, by));
            bot.submitWithin(handle, told, by);
            return new Booking.Booked(told);
        });
    }

    private static AuditLine journalLine(
            final PaymentRequest request, final Arrival arrival, final Tiers.Settlement settlement, final Actor by) {
        final Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reference", request.reference());
        facts.put("matchedBy", arrival.matchedBy().name());
        if (arrival.bunqPaymentId() != null) {
            facts.put("bunqPayment", arrival.bunqPaymentId());
            facts.put("receivedCents", arrival.receivedCents());
        }
        facts.put("orderedDays", request.days());
        facts.put("days", settlement.days());
        facts.put("donationCents", settlement.donationCents());
        facts.put("downgraded", settlement.downgraded());
        return AuditLine.about(ACTION, by, request.discordId(), facts);
    }
}
