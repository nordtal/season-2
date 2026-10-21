package eu.nordtal.season.database.payment;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.access.AccessGrant;
import eu.nordtal.season.database.access.AccessSource;
import eu.nordtal.season.database.access.Grants;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.messages.value.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Books a payment in one transaction: marks the request paid, appends the access, sets the donor flag and journals it.
 * The bot is told in the same transaction. Only steward calls it.
 */
public final class Bookings {

    private final Jdbi jdbi;
    private final Inbox<BotRequest> bot;

    /** Borrows {@code dataSource}. The process that built it closes it. */
    public Bookings(final DataSource dataSource) {
        this.jdbi = Jdbis.over(dataSource);
        this.bot = Inbox.over(dataSource, BotRequest.TABLE);
    }

    /**
     * What arrived for a request.
     *
     * @param matchedBy the tab, the reference or an admin's word
     * @param bunqPaymentId the bank's payment, null for a booking by hand
     * @param receivedCents the amount, null for a booking by hand
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

        /** Money that the poll found at the bank. */
        public static Arrival paid(final long bunqPaymentId, final int receivedCents, final PaymentMatch matchedBy) {
            return new Arrival(matchedBy, bunqPaymentId, receivedCents);
        }

        /** An admin's word that the money arrived. */
        public static Arrival byHand() {
            return new Arrival(PaymentMatch.MANUAL, null, null);
        }
    }

    /** How one booking ended. */
    public sealed interface Booking {

        /** Booked and committed. The bot tells the payer this. */
        record Booked(BookedPayment told) implements Booking {}

        /** The request was not open, so nothing was written. */
        record NotOpen() implements Booking {}

        /** The money does not cover the cheapest tier, so nothing was written. A human decides. */
        record BelowMinimum(PaymentRequest request) implements Booking {}
    }

    /**
     * Books what arrived for one request.
     *
     * @param rule what the order buys with that money, read off the locked row. Empty if it buys nothing
     * @param by who booked it: steward for the poll, the admin for a booking by hand
     * @throws org.jdbi.v3.core.statement.UnableToExecuteStatementException if another request booked this bank
     *     payment, which rolls back everything
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
            final UUID entry = Journal.write(handle, journalLine(open, arrival, settlement, by));
            final BookedPayment told = new BookedPayment(
                    open.id(),
                    open.discordId(),
                    open.reference(),
                    settlement.days(),
                    settlement.donationCents(),
                    settlement.downgraded(),
                    arrival.receivedCents(),
                    grant.validFrom(),
                    grant.validUntil(),
                    entry);
            bot.submitWithin(handle, new BotRequest.PaymentBooked(open.id(), entry), by);
            return new Booking.Booked(told);
        });
    }

    /**
     * Reads what a booking gave its payer back from the payment request, its grant and the journal entry.
     * The order's total is what the payer was asked for, so less money than that was a downgrade.
     */
    public BookedPayment told(final BotRequest.PaymentBooked booked) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT p.discord_id, p.reference, b.days, b.donation_cents,
                               coalesce(p.matched_cents < p.amount_cents, false) AS downgraded,
                               p.matched_cents, g.valid_from, g.valid_until
                        FROM payment_request p
                        JOIN access_grant g ON g.payment_request_id = p.id
                        JOIN journal_booking b ON b.entry = :entry
                        WHERE p.id = :payment
                        """)
                .bind("payment", booked.payment())
                .bind("entry", booked.entry())
                .map((row, context) -> new BookedPayment(
                        booked.payment(),
                        DiscordId.of(row.getString("discord_id")),
                        row.getString("reference"),
                        row.getInt("days"),
                        row.getInt("donation_cents"),
                        row.getBoolean("downgraded"),
                        row.getObject("matched_cents", Integer.class),
                        row.getTimestamp("valid_from").toInstant(),
                        row.getTimestamp("valid_until").toInstant(),
                        booked.entry()))
                .findOne()
                .orElseThrow(() -> new IllegalStateException(
                        "payment " + booked.payment() + " has no booking with entry " + booked.entry())));
    }

    private static AuditLine journalLine(
            final PaymentRequest request, final Arrival arrival, final Tiers.Settlement settlement, final Actor by) {
        final Integer received = arrival.receivedCents();
        return AuditLine.about(
                JournalAction.SETTLE,
                by,
                request.discordId(),
                new JournalDetail.Settled(
                        request.id(),
                        request.reference(),
                        arrival.matchedBy(),
                        settlement.days(),
                        request.days(),
                        received == null ? null : Money.euroCents(received),
                        Money.euroCents(settlement.donationCents())));
    }
}
