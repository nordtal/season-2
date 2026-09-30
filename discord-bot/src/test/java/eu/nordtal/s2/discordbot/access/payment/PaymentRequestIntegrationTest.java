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
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AccessSource;
import eu.nordtal.s2.database.payment.PaymentMatch;
import eu.nordtal.s2.database.payment.PaymentNotice;
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
                .useHandle(handle ->
                        handle.execute("TRUNCATE access_grant, payment_request, expiry_notice, payment_notice, "
                                + "account_link, link_code, audit_log, discord_user, payment_gateway CASCADE"));
        requests = new PaymentRequests(database.jdbi());
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
                        "payment_notice",
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
        assertTrue(requests.close(first.id(), PaymentRequestStatus.SUPERSEDED));

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
    void oneBunqPaymentCanOnlyEverBeBookedOnce() {
        final PaymentRequest first = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.settle(first.id(), 777L));
        requests.close(first.id(), PaymentRequestStatus.CANCELLED); // no-op: it is PAID

        final PaymentRequest second = requests.open(DiscordId.of(OTHER), 30, 300, 0, TTL_HOURS);

        assertFalse(requests.settle(second.id(), 777L));
        assertTrue(requests.alreadyBooked(777L));
    }

    @Test
    void settlingTwiceBooksOnce() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        assertAll(
                () -> assertTrue(requests.settle(request.id(), 888L)),
                () -> assertFalse(requests.settle(request.id(), 888L), "the row is no longer OPEN"));
    }

    @Test
    void aManualSettlementBooksWithoutABunqPaymentId() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        assertTrue(requests.settleManually(request.id()));

        final PaymentRequest reloaded = requests.recentOf(DiscordId.of(USER), 1).getFirst();
        assertAll(
                () -> assertEquals(PaymentRequestStatus.PAID, reloaded.status()),
                () -> assertNotNull(reloaded.settled()),
                () -> assertEquals(
                        null,
                        reloaded.bunqPaymentId(),
                        "a manual settlement is told apart from a matched one by having no payment"));
    }

    @Test
    void oneRequestCanOnlyEverProduceOneGrant() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.settle(request.id(), 999L);

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
        requests.settle(request.id(), 1234L);
        final AccessGrant second = access.grantAccess(DiscordId.of(USER), 30, AccessSource.PURCHASE, request.id());

        assertAll(
                () -> assertEquals(first.validUntil(), second.validFrom(), "renewing early never loses paid time"),
                () -> assertEquals(Duration.ofDays(30), Duration.between(second.validFrom(), second.validUntil())),
                () -> assertTrue(
                        second.validUntil().isAfter(Instant.now().plus(Duration.ofDays(59))),
                        "30 days on top of 30 days"));
    }

    @Test
    void aRequestThatWantsATabTurnsUpInTheWorkersQueueAndOnlyThen() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.tabsToCreate().isEmpty(), "choosing a tier is not asking for a payment link");

        assertTrue(requests.requestTab(request.id()));

        assertAll(
                () -> assertEquals(List.of(request.reference()), references(requests.tabsToCreate())),
                () -> assertNotNull(requests.tabsToCreate().getFirst().tabRequested()),
                () -> assertNull(requests.tabsToCreate().getFirst().tabFailed()));
    }

    @Test
    void theQueueEmptiesTheMomentTheTabExists() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());
        assertEquals(1, requests.tabsToCreate().size(), "queued");

        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        // Otherwise the worker makes a second tab on its next pass.
        assertTrue(requests.tabsToCreate().isEmpty(), "a request with a tab is not waiting for one");
        assertFalse(requests.requestTab(request.id()), "and asking again changes nothing");
    }

    @Test
    void aRefusedTabLeavesTheQueueSaysWhyAndCanBeAskedForAgain() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());

        assertTrue(requests.failTab(request.id(), "bunq: MonetaryAccount not found"));

        final PaymentRequest failed = requests.openOf(DiscordId.of(USER)).orElseThrow();
        assertAll(
                () -> assertTrue(
                        requests.tabsToCreate().isEmpty(),
                        "a failure retried on every pass is a failure repeated forever"),
                () -> assertNull(failed.tabRequested()),
                () -> assertEquals(
                        "bunq: MonetaryAccount not found", failed.tabFailed(), "'der Link kommt gleich' needs an exit"),
                () -> assertTrue(requests.requestTab(request.id())),
                () -> assertNull(
                        requests.openOf(DiscordId.of(USER)).orElseThrow().tabFailed(),
                        "asking again clears the old reason rather than showing it next to a pending ask"));
    }

    @Test
    void aRequestAlreadyAskedToBeCancelledIsNeverGivenATab() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());
        assertTrue(requests.requestCancel(request.id()));

        // Otherwise the window before the status is written produces a tab only to cancel.
        assertTrue(requests.tabsToCreate().isEmpty());
    }

    @Test
    void aCancelIsQueuedOnceAndLeavesTheQueueWhenTheTabIsGone() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");
        assertTrue(requests.tabsToCancel().isEmpty());

        assertTrue(requests.requestCancel(request.id()));
        assertFalse(requests.requestCancel(request.id()), "asking twice does not move the timestamp");
        assertTrue(requests.close(request.id(), PaymentRequestStatus.CANCELLED));

        assertEquals(
                List.of(request.reference()),
                references(requests.tabsToCancel()),
                "closing the row is not cancelling the tab at bunq");

        assertTrue(requests.recordCancelled(request.id()));
        assertTrue(
                requests.tabsToCancel().isEmpty(),
                "without an exit the worker cancels the same tab on every pass, forever");
    }

    @Test
    void aMatchIsWrittenOntoARowThatIsStillOpenSoTheSettledIffPaidCheckHolds() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");

        assertTrue(requests.recordMatch(request.id(), 4711L, 300, PaymentMatch.TAB));

        final PaymentRequest matched = requests.openOf(DiscordId.of(USER)).orElseThrow();
        assertAll(
                () -> assertEquals(
                        PaymentRequestStatus.OPEN, matched.status(), "the worker finds the money; the bot books it"),
                () -> assertNull(matched.settled()),
                () -> assertEquals(300, matched.matchedCents()),
                () -> assertEquals(PaymentMatch.TAB, matched.matchedBy()),
                () -> assertEquals(4711L, matched.bunqPaymentId()),
                () -> assertTrue(
                        requests.alreadyBooked(4711L), "the claim on the payment id happens here, not at the booking"));
    }

    @Test
    void oneBunqPaymentCannotBeAttributedToTwoRequests() {
        final PaymentRequest first = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        assertTrue(requests.recordMatch(first.id(), 4711L, 300, PaymentMatch.TAB));

        final PaymentRequest second = requests.open(DiscordId.of(OTHER), 30, 300, 0, TTL_HOURS);

        // recordMatch passes the unique violation on rather than hiding it.
        final UnableToExecuteStatementException failure = assertThrows(
                UnableToExecuteStatementException.class,
                () -> requests.recordMatch(second.id(), 4711L, 300, PaymentMatch.REFERENCE));
        assertTrue(
                String.valueOf(failure.getMessage()).contains("payment_request_bunq_payment_id_key"),
                failure.getMessage());
    }

    private static List<String> references(final List<PaymentRequest> found) {
        return found.stream().map(PaymentRequest::reference).toList();
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
    void aPaymentIsRaisedToTheAdminChannelExactlyOnceHoweverOftenItIsPolled() {
        // bunq keeps returning the same payment, so the notice must not repeat every poll.
        assertAll(
                () -> assertTrue(requests.noticeOnce(555L, "UNMATCHED", "first")),
                () -> assertFalse(requests.noticeOnce(555L, "UNMATCHED", "second poll")),
                () -> assertFalse(requests.noticeOnce(555L, "UNMATCHED", "third poll")));
    }

    @Test
    void aNoticeWaitsInTheTableUntilSomebodyClaimsItAndIsClaimedOnlyOnce() {
        // The posted column makes the row a queue, so a bot dying between write and post keeps it.
        requests.noticeOnce(555L, "UNMATCHED", "56.00 EUR with no reference");
        requests.noticeOnce(556L, "EXPIRED_REFERENCE", "NT-ABCDEF is not open");

        assertEquals(
                List.of(555L, 556L),
                requests.unpostedNotices().stream()
                        .map(PaymentNotice::bunqPaymentId)
                        .toList(),
                "oldest first, so the admin channel reads in the order the money arrived");
        assertEquals(
                "56.00 EUR with no reference",
                requests.unpostedNotices().getFirst().detail());
        assertEquals("UNMATCHED", requests.unpostedNotices().getFirst().reason());

        assertTrue(requests.claimNotice(555L));
        assertFalse(
                requests.claimNotice(555L),
                "two bots, or one bot and a poll racing itself, must not both post the same line");

        assertEquals(
                List.of(556L),
                requests.unpostedNotices().stream()
                        .map(PaymentNotice::bunqPaymentId)
                        .toList(),
                "a claimed notice leaves the queue; without that it is posted on every pass forever");
    }

    @Test
    void aNoticeNobodyClaimsStaysInTheQueueAcrossARestart() {
        requests.noticeOnce(557L, "UNMATCHED", "money nobody heard about");

        // Written by one container, read by another.
        assertEquals(1, requests.unpostedNotices().size());
        assertEquals(1, requests.unpostedNotices().size(), "reading is not claiming");
    }

    @Test
    void matchedMoneyWaitsInAQueueOfItsOwnUntilItIsBooked() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");
        assertTrue(requests.matchedAwaitingBooking().isEmpty(), "an open request with a tab is not money");

        assertTrue(requests.recordMatch(request.id(), 4711L, 500, PaymentMatch.REFERENCE));

        final List<PaymentRequest> queue = requests.matchedAwaitingBooking();
        assertEquals(List.of(request.reference()), references(queue));
        assertAll(
                // The grant comes from what arrived, and this row has no bunq payment.
                () -> assertEquals(500, queue.getFirst().matchedCents()),
                () -> assertEquals(4711L, queue.getFirst().bunqPaymentId()),
                () -> assertEquals(PaymentMatch.REFERENCE, queue.getFirst().matchedBy()));

        assertTrue(requests.settle(request.id(), 4711L));
        assertTrue(
                requests.matchedAwaitingBooking().isEmpty(),
                "booking is the exit; without it the same money is granted on every pass");
    }

    @Test
    void aManualSettlementNeverEntersTheBookingQueue() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        assertTrue(requests.settleManually(request.id()));

        final PaymentRequest settled = requests.byId(request.id()).orElseThrow();
        assertAll(
                () -> assertEquals(PaymentRequestStatus.PAID, settled.status()),
                () -> assertEquals(
                        PaymentMatch.MANUAL,
                        settled.matchedBy(),
                        "an audit that cannot tell a hand-granted request from a matched one is"
                                + " missing the only thing anybody asks it afterwards"),
                () -> assertNull(
                        settled.matchedCents(),
                        "nothing arrived, so there is no amount to record - and that is exactly why"
                                + " matchedAwaitingBooking keys on matched_cents"),
                () -> assertTrue(requests.matchedAwaitingBooking().isEmpty()));
    }

    @Test
    void closingARequestAndAskingForItsTabToGoAwayIsOneTransaction() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");

        assertTrue(requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.EXPIRED));

        final PaymentRequest closed = requests.byId(request.id()).orElseThrow();
        assertAll(
                () -> assertEquals(PaymentRequestStatus.EXPIRED, closed.status()),
                () -> assertNotNull(
                        closed.cancelRequested(),
                        "a closed row whose bunq.me URL still works is a link somebody can pay,"
                                + " and that payment lands on a reference nothing books"),
                () -> assertEquals(List.of(request.reference()), references(requests.tabsToCancel())));
    }

    @Test
    void closeAndRequestCancelRefusesToBeUsedAsASettlement() {
        final PaymentRequest request = requests.open(DiscordId.of(USER), 30, 300, 0, TTL_HOURS);

        // PAID means something has to be granted, never a row closed with nothing.
        assertThrows(
                IllegalArgumentException.class,
                () -> requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.PAID));
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
