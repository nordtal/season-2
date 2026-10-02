package eu.nordtal.s2.steward.bunq;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.inbox.BankRequest;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.payment.Bookings;
import eu.nordtal.s2.database.payment.PaymentMatch;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.payment.PaymentRequestStatus;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.database.payment.Tier;
import eu.nordtal.s2.database.payment.Tiers;
import eu.nordtal.s2.internalapi.BankWire;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.InternalServer;
import io.javalin.Javalin;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A pass against a stand-in for steward-bunq and a real database, logged in as steward.
 * What a match books, and that a booking is everything or nothing.
 */
class PaymentsTest {

    private static final String TOKEN = "bank-token";
    private static final DiscordId PAYER = DiscordId.of("100000000000000001");
    private static final DiscordId OTHER = DiscordId.of("100000000000000002");
    private static final Tiers PRICES = Tiers.of(List.of(new Tier(30, 300), new Tier(60, 500), new Tier(90, 700)), 500);

    /** What the stand-in answers for a tab's payments, by tab id. */
    private static final Map<Long, List<BankWire.Payment>> TAB_PAYMENTS = new ConcurrentHashMap<>();

    /** What the stand-in answers for the recent payments. */
    private static final List<BankWire.Payment> RECENT = new CopyOnWriteArrayList<>();

    private static Javalin bunq;
    private static Bank bank;
    private static DataSource owner;
    private static DataSource steward;

    private PaymentRequests requests;
    private Payments payments;

    @BeforeAll
    static void start() {
        bunq = new InternalServer(BankWire.SERVICE, Map.of("NORDTAL_STEWARD_BUNQ_TOKEN", TOKEN)::get)
                .start(0, config -> {
                    config.routes.get(
                            BankWire.TAB_PAYMENTS,
                            ctx -> ctx.json(TAB_PAYMENTS.getOrDefault(Long.parseLong(ctx.pathParam("id")), List.of())));
                    config.routes.get(BankWire.RECENT, ctx -> ctx.json(RECENT));
                });
        bank = new Bank(
                new InternalClient(BankWire.SERVICE, "http://127.0.0.1:" + bunq.port(), TOKEN, Duration.ofSeconds(5)));
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        // The role steward logs in as, so a statement it was never granted fails here first.
        steward = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @AfterAll
    static void stop() {
        if (bunq != null) {
            bunq.stop();
        }
    }

    @BeforeEach
    void freshTables() {
        TAB_PAYMENTS.clear();
        RECENT.clear();
        owner().useHandle(handle -> handle.execute("TRUNCATE access_grant, payment_request, audit_log, bot_inbox,"
                + " bank_inbox, admin_alert, discord_user CASCADE"));
        requests = new PaymentRequests(owner);
        payments = new Payments(
                bank,
                new PaymentRequests(steward),
                new Bookings(steward),
                PRICES,
                AlertBook.using(steward),
                Inbox.over(steward, BankRequest.TABLE),
                Instant.EPOCH,
                20);
    }

    @Test
    void aPaymentOnItsTabIsBookedWithItsAccessDonorFlagJournalLineAndWordToTheBot() {
        // 30 days and the donation, paid in full on the tab.
        final PaymentRequest request = requests.open(PAYER, 30, 800, 500, 24);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");
        TAB_PAYMENTS.put(4242L, List.of(new BankWire.Payment(4711L, 800, "2026-10-01T10:00:00Z", "x")));

        payments.pass();

        final PaymentRequest booked = requests.byId(request.id()).orElseThrow();
        final List<BotRequest> told = told();
        assertAll(
                () -> assertEquals(PaymentRequestStatus.PAID, booked.status()),
                () -> assertEquals(4711L, booked.bunqPaymentId()),
                () -> assertEquals(800, booked.matchedCents()),
                () -> assertEquals(PaymentMatch.TAB, booked.matchedBy()),
                () -> assertEquals(
                        1,
                        count("SELECT count(*) FROM access_grant WHERE source = 'PURCHASE'"
                                + " AND payment_request_id = '" + request.id() + "'")),
                () -> assertEquals(1, count("SELECT count(*) FROM discord_user WHERE donor")),
                () -> assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'SETTLE'")),
                () -> assertEquals(1, told.size()),
                () -> assertTrue(
                        told.getFirst() instanceof BotRequest.PaymentBooked paid
                                && paid.days() == 30
                                && paid.donationCents() == 500
                                && !paid.downgraded()
                                && paid.person().equals(PAYER),
                        told.toString()));

        // bunq answers the same payment on the next pass, which books nothing twice.
        payments.pass();
        assertEquals(1, told().size());
    }

    @Test
    void aShortPaymentBooksTheLongestTierItCovers() {
        final PaymentRequest request = requests.open(PAYER, 90, 700, 0, 24);
        RECENT.add(new BankWire.Payment(4800L, 500, "2026-10-01T10:00:00Z", "for " + request.reference()));

        payments.pass();

        final BotRequest.PaymentBooked paid = (BotRequest.PaymentBooked) told().getFirst();
        assertAll(
                () -> assertEquals(60, paid.days()),
                () -> assertTrue(paid.downgraded()),
                () -> assertEquals(
                        PaymentMatch.REFERENCE,
                        requests.byId(request.id()).orElseThrow().matchedBy()));
    }

    @Test
    void aPaymentThatCoversNoTierIsRaisedOnceAndBooksNothing() {
        final PaymentRequest request = requests.open(PAYER, 30, 300, 0, 24);
        RECENT.add(new BankWire.Payment(4900L, 100, "2026-10-01T10:00:00Z", request.reference()));

        payments.pass();
        payments.pass();

        assertAll(
                () -> assertEquals(
                        PaymentRequestStatus.OPEN,
                        requests.byId(request.id()).orElseThrow().status()),
                () -> assertEquals(0, told().size()),
                () -> assertEquals(
                        1,
                        count("SELECT count(*) FROM admin_alert WHERE type = 'PAYMENT' AND source = 'payment:4900'")));
    }

    @Test
    void aPaymentClaimedTwiceWritesNothingForTheSecondClaim() {
        final PaymentRequest first = requests.open(PAYER, 30, 300, 0, 24);
        final PaymentRequest second = requests.open(OTHER, 30, 300, 0, 24);
        final Bookings bookings = new Bookings(steward);
        bookings.book(
                first.id(),
                Bookings.Arrival.paid(4711L, 300, PaymentMatch.TAB),
                order -> PRICES.resolve(300, order),
                Actor.STEWARD);

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> bookings.book(
                        second.id(),
                        Bookings.Arrival.paid(4711L, 300, PaymentMatch.REFERENCE),
                        order -> PRICES.resolve(300, order),
                        Actor.STEWARD));

        // The grant, the journal line and the word to the bot went with the failed update.
        assertAll(
                () -> assertEquals(
                        PaymentRequestStatus.OPEN,
                        requests.byId(second.id()).orElseThrow().status()),
                () -> assertEquals(1, count("SELECT count(*) FROM access_grant")),
                () -> assertEquals(1, count("SELECT count(*) FROM audit_log")),
                () -> assertEquals(1, told().size()));
    }

    @Test
    void aBookingByHandIsTheSameBookingWithTheAdminAsItsActor() {
        final PaymentRequest request = requests.open(PAYER, 60, 500, 0, 24);
        final Actor admin = Actor.person(OTHER);

        final Bookings.Booking booking = new Bookings(steward)
                .book(request.id(), Bookings.Arrival.byHand(), order -> Optional.of(order.asOrdered()), admin);

        final PaymentRequest booked = requests.byId(request.id()).orElseThrow();
        assertAll(
                () -> assertTrue(booking instanceof Bookings.Booking.Booked, booking.toString()),
                () -> assertEquals(PaymentMatch.MANUAL, booked.matchedBy()),
                () -> assertEquals(null, booked.bunqPaymentId()),
                () -> assertEquals(null, booked.matchedCents()),
                () -> assertEquals(
                        1,
                        count("SELECT count(*) FROM bot_inbox WHERE actor_kind = 'PERSON' AND actor_id = '"
                                + OTHER.value() + "'")),
                () -> assertTrue(
                        new Bookings(steward)
                                        .book(
                                                request.id(),
                                                Bookings.Arrival.byHand(),
                                                order -> Optional.of(order.asOrdered()),
                                                admin)
                                instanceof Bookings.Booking.NotOpen,
                        "a second click books nothing"));
    }

    private static Jdbi owner() {
        return Jdbis.over(owner);
    }

    private static int count(final String sql) {
        return owner().withHandle(
                        handle -> handle.createQuery(sql).mapTo(Integer.class).one());
    }

    /** What the bot was asked, oldest first. */
    private static List<BotRequest> told() {
        return Inbox.<BotRequest>over(owner, BotRequest.TABLE).recent(BotRequest.PaymentBooked.class, 50).stream()
                .map(eu.nordtal.s2.database.inbox.Request::payload)
                .toList()
                .reversed();
    }
}
