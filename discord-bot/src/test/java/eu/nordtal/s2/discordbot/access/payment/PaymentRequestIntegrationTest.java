package eu.nordtal.s2.discordbot.access.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AccessSource;
import eu.nordtal.s2.database.inbox.BankRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.payment.PaymentRequestStatus;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.database.payment.Watermark;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The payment request state machine against a real PostgreSQL and the real migrations.
 *
 * Every rule here is a constraint or an index; the test skips itself when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRequestIntegrationTest {

    private static final String USER = "100000000000000001";
    private static final String OTHER = "100000000000000002";
    private static final int TTL_HOURS = 24;

    private static TestDatabase postgres;
    private static Database database;

    private PaymentRequests requests;
    private AccessDirectory access;

    @BeforeAll
    static void startDatabase() {
        postgres = TestDatabase.fresh();

        database = Database.create(DatabaseConfig.of(postgres.jdbcUrl(), postgres.username(), postgres.password()));
        database.jdbi().installPlugin(Jdbis.ids());
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clean() {
        assumeTrue(database != null);
        database.jdbi()
                .useHandle(handle -> handle.execute("TRUNCATE access_grant, payment_request, expiry_notice, "
                        + "account_link, link_code, audit_log, discord_user, payment_gateway, bank_inbox CASCADE"));
        requests = new PaymentRequests(database.dataSource());
        access = AccessDirectory.using(database.dataSource(), Clock.systemUTC());
    }

    @Test
    void theMigrationAppliesAndCreatesTheStageBTablesToo() {
        final List<String> tables = database.jdbi()
                .withHandle(handle -> handle.createQuery(
                                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
                        .mapTo(String.class)
                        .list());

        assertTrue(
                tables.containsAll(List.of(
                        "access_grant",
                        "account_link",
                        "audit_log",
                        "discord_user",
                        "expiry_notice",
                        "link_code",
                        "managed_message",
                        "payment_gateway",
                        "payment_request")),
                tables.toString());
    }

    @Test
    void aSecondOpenRequestForTheSamePersonIsRefusedByTheDatabase() {
        requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        // A partial unique index, not a Java check two threads could both pass.
        assertThrows(
                UnableToExecuteStatementException.class,
                () -> requests.open(DiscordId.of(USER), 60, 500, 0, TTL_HOURS));
    }

    @Test
    void closingTheOldRequestIsWhatMakesANewOnePossible() {
        final PaymentRequest first = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.closeAndRequestCancel(first.id(), PaymentRequestStatus.SUPERSEDED, Actor.STEWARD));

        final PaymentRequest second = requests.open(DiscordId.of(USER), 60, 500, 0, TTL_HOURS);

        assertAll(
                () -> assertEquals(60, second.days()),
                () -> assertEquals(
                        Optional.of(second.id()),
                        requests.openOf(DiscordId.of(USER)).map(PaymentRequest::id)),
                () -> assertFalse(first.reference().equals(second.reference()), "each request gets its own reference"));
    }

    @Test
    void twoPeopleCanEachHaveAnOpenRequest() {
        requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.open(DiscordId.of(OTHER), 30, 300, 0, TTL_HOURS);

        assertAll(
                () -> assertTrue(requests.openOf(DiscordId.of(USER)).isPresent()),
                () -> assertTrue(requests.openOf(DiscordId.of(OTHER)).isPresent()),
                () -> assertEquals(2, requests.allOpen().size()));
    }

    @Test
    void theReferenceMatchesThePatternTheFallbackMatcherScansFor() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(
                PaymentRequests.REFERENCE_PATTERN.matcher(request.reference()).matches(), request.reference());
    }

    @Test
    void anUnconfirmedRequestIsEditedInPlaceRatherThanReplaced() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        assertTrue(requests.reselect(request.id(), 60, 1000, 500));

        final PaymentRequest reloaded = requests.openOf(DiscordId.of(USER)).orElseThrow();
        assertAll(
                () -> assertEquals(
                        request.reference(),
                        reloaded.reference(),
                        "clicking through the options must not burn a reference per click"),
                () -> assertEquals(60, reloaded.days()),
                () -> assertEquals(1000, reloaded.amountCents()),
                () -> assertTrue(reloaded.donationRequested()));
    }

    @Test
    void onceATabExistsTheRequestCanNoLongerBeEdited() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        assertFalse(requests.reselect(request.id(), 60, 500, 0));
    }

    @Test
    void aRequestPastItsTtlTurnsUpInTheExpirySweep() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.dueForExpiry().isEmpty(), "not due yet");

        database.jdbi()
                .useHandle(handle -> handle.createUpdate(
                                "UPDATE payment_request SET expires = now() - interval '1 minute' WHERE id = :id")
                        .bind("id", request.id())
                        .execute());

        assertEquals(
                List.of(request.reference()),
                requests.dueForExpiry().stream().map(PaymentRequest::reference).toList());
    }

    @Test
    void oneRequestCanOnlyEverProduceOneGrant() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        access.grantAccess(DiscordId.of(USER), 30, AccessSource.PURCHASE, request.id());

        // Two code paths settling once each still cannot double-grant.
        assertThrows(
                RuntimeException.class,
                () -> access.grantAccess(DiscordId.of(USER), 30, AccessSource.PURCHASE, request.id()));
    }

    @Test
    void aDowngradedPaymentGrantsTheDaysItCoveredAppendedToRunningAccess() {
        // Ordered 90 days, paid for 30; the grant is appended, not restarted.
        final AccessGrant first = access.grantAccess(DiscordId.of(USER), 30, AccessSource.ADMIN, null);

        final PaymentRequest request = requests.open(DiscordId.of(USER), 90, 700, 0, TTL_HOURS);
        final AccessGrant second = access.grantAccess(DiscordId.of(USER), 30, AccessSource.PURCHASE, request.id());

        assertAll(
                () -> assertEquals(first.validUntil(), second.validFrom(), "renewing early never loses paid time"),
                () -> assertEquals(Duration.ofDays(30), Duration.between(second.validFrom(), second.validUntil())),
                () -> assertTrue(
                        second.validUntil().isAfter(Instant.now().plus(Duration.ofDays(59))),
                        "30 days on top of 30 days"));
    }

    /** The bank's inbox, as steward would read it. */
    private Inbox<BankRequest> bank() {
        return Inbox.over(database.dataSource(), BankRequest.TABLE);
    }

    /** Every request waiting in the bank's inbox, oldest first. */
    private List<BankRequest> asked() {
        return bank().recent(BankRequest.OpenTab.class, 50).stream()
                .map(Request::payload)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new))
                .reversed();
    }

    @Test
    void aRequestThatWantsATabIsAskedOfTheBankAndOnlyThen() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(asked().isEmpty(), "choosing a tier is not asking for a payment link");

        assertTrue(requests.requestTab(request.id(), Actor.person(DiscordId.of(USER))));

        assertEquals(List.of(new BankRequest.OpenTab(request.id())), asked());
        final Request<BankRequest> ask = bank().claim().orElseThrow();
        assertEquals(Actor.person(DiscordId.of(USER)), ask.actor(), "the payer asked, not the system");
    }

    @Test
    void aRequestWithATabIsNotAskedForAnother() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        // Otherwise steward makes a second tab.
        assertFalse(requests.requestTab(request.id(), Actor.person(DiscordId.of(USER))), "asking changes nothing");
        assertTrue(asked().isEmpty());
    }

    @Test
    void aRefusedTabSaysWhyAndAskingAgainClearsTheReason() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id(), Actor.person(DiscordId.of(USER)));

        assertTrue(requests.failTab(request.id(), "bunq: MonetaryAccount not found"));

        final PaymentRequest failed = requests.openOf(DiscordId.of(USER)).orElseThrow();
        assertAll(
                () -> assertEquals(
                        "bunq: MonetaryAccount not found", failed.tabFailed(), "'der Link kommt gleich' needs an exit"),
                () -> assertTrue(requests.requestTab(request.id(), Actor.person(DiscordId.of(USER)))),
                () -> assertNull(
                        requests.openOf(DiscordId.of(USER)).orElseThrow().tabFailed(),
                        "asking again clears the old reason rather than showing it next to a pending ask"));
    }

    @Test
    void aTabIsRecordedCancelledOnceAndOnlyWhenThereIsOne() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertFalse(requests.recordCancelled(request.id()), "there is no tab to have cancelled");
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");

        assertTrue(requests.recordCancelled(request.id()));
        assertFalse(requests.recordCancelled(request.id()), "without an exit steward cancels it forever");
        assertNotNull(requests.byId(request.id()).orElseThrow().tabCancelled());
    }

    @Test
    void theFirstStartStampsTheWatermarkAndNoLaterStartMovesIt() throws Exception {
        final Instant before = Instant.now();
        final Instant first = Watermark.resolve(database.jdbi(), "", Instant.now());

        // Long enough that a second "now" would be a different instant.
        Thread.sleep(50);
        final Instant second = Watermark.resolve(database.jdbi(), "", Instant.now());

        assertAll(
                () -> assertFalse(first.isBefore(before.minusSeconds(1))),
                () -> assertEquals(
                        first,
                        second,
                        "a restart must not move the cut-off forward - everything between the two "
                                + "would be ignored forever"),
                () -> assertTrue(Watermark.stored(database.jdbi()).isPresent()));
    }

    @Test
    void anOverrideWinsButDoesNotReplaceTheStoredValue() {
        final Instant stored = Watermark.resolve(database.jdbi(), "", Instant.now());

        final Instant overridden = Watermark.resolve(database.jdbi(), "2020-01-01T00:00:00Z", Instant.now());
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), overridden);

        // Emptying the override falls back to the first-start instant, not the restart.
        assertEquals(stored, Watermark.resolve(database.jdbi(), "", Instant.now()));
    }

    @Test
    void theWatermarkExistsBeforeTheFirstPollEvenWhenAnOverrideIsSet() {
        Watermark.resolve(database.jdbi(), "2020-01-01T00:00:00Z", Instant.now());

        assertTrue(Watermark.stored(database.jdbi()).isPresent());
    }

    @Test
    void closingARequestAsksTheBankForItsTabToGoAway() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");

        assertTrue(requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.EXPIRED, Actor.STEWARD));

        assertEquals(
                PaymentRequestStatus.EXPIRED,
                requests.byId(request.id()).orElseThrow().status());
        // A closed row whose bunq.me URL still works is a link somebody can pay, onto a reference nothing books.
        assertEquals(
                List.of(new BankRequest.CancelTab(request.id())),
                bank().recent(BankRequest.CancelTab.class, 10).stream()
                        .map(Request::payload)
                        .toList());
    }

    @Test
    void closeAndRequestCancelRefusesToBeUsedAsASettlement() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        // PAID means something has to be granted, never a row closed with nothing.
        assertThrows(
                IllegalArgumentException.class,
                () -> requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.PAID, Actor.STEWARD));
        assertEquals(
                PaymentRequestStatus.OPEN,
                requests.byId(request.id()).orElseThrow().status());
    }

    @Test
    void byIdAnswersTheOneRowTheWaitingPurchaseMessageIsAbout() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        assertEquals(
                request.reference(), requests.byId(request.id()).orElseThrow().reference());
        assertTrue(requests.byId(java.util.UUID.randomUUID()).isEmpty());
    }
}
