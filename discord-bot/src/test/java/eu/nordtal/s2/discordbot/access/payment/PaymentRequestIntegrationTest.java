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
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.payment.PaymentMatch;
import eu.nordtal.s2.common.payment.PaymentNotice;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.common.payment.Watermark;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The payment request state machine against a real PostgreSQL, running the real migrations.
 *
 * An in-memory stand-in would prove nothing here: every rule this exercises is a constraint or an index in the
 * schema - one open request per person, one grant per request, one booking per bunq payment - and the reference
 * retry only matters because a unique violation is a real error with a real SQLSTATE.
 *
 * Testcontainers is driven by hand from {@link BeforeAll} rather than through the {@code @Testcontainers} extension:
 * that extension ships in {@code org.testcontainers:junit-jupiter}, which is built against JUnit 5, and this repo is
 * on the JUnit 6 BOM. The test skips itself when no Docker daemon is reachable - so a green build on a machine
 * without Docker proves less than it looks.
 *
 * What this cannot prove: anything about bunq or about Discord. Tab creation, tab cancellation and result inquiries
 * need the bunq sandbox; buttons, modals, ephemeral messages and role assignment need a real guild.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRequestIntegrationTest {

    private static final String USER = "100000000000000001";
    private static final String OTHER = "100000000000000002";
    private static final int TTL_HOURS = 24;

    private static PostgreSQLContainer<?> postgres;
    private static Database database;

    private PaymentRequests requests;
    private AccessDirectory access;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        database = Database.create(
                DatabaseConfig.of(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clean() {
        assumeTrue(database != null);
        database.jdbi()
                .useHandle(handle ->
                        handle.execute("TRUNCATE access_grant, payment_request, expiry_notice, payment_notice, "
                                + "account_link, link_code, audit_log, discord_user, bot_setting CASCADE"));
        requests = new PaymentRequests(database.jdbi());
        access = AccessDirectory.using(database.dataSource());
    }

    // The schema's own rules.

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
                        "bot_setting",
                        "discord_user",
                        "expiry_notice",
                        "link_code",
                        "managed_message",
                        "payment_notice",
                        "payment_request")),
                tables.toString());
    }

    @Test
    void aSecondOpenRequestForTheSamePersonIsRefusedByTheDatabase() {
        requests.open(USER, 30, 300, 0, TTL_HOURS);

        // Not a check in Java that two threads could both pass - a partial unique index.
        assertThrows(UnableToExecuteStatementException.class, () -> requests.open(USER, 60, 500, 0, TTL_HOURS));
    }

    @Test
    void closingTheOldRequestIsWhatMakesANewOnePossible() {
        final PaymentRequest first = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.close(first.id(), PaymentRequestStatus.SUPERSEDED));

        final PaymentRequest second = requests.open(USER, 60, 500, 0, TTL_HOURS);

        assertAll(
                () -> assertEquals(60, second.days()),
                () -> assertEquals(
                        Optional.of(second.id()), requests.openOf(USER).map(PaymentRequest::id)),
                () -> assertFalse(first.reference().equals(second.reference()), "each request gets its own reference"));
    }

    @Test
    void twoPeopleCanEachHaveAnOpenRequest() {
        requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.open(OTHER, 30, 300, 0, TTL_HOURS);

        assertAll(
                () -> assertTrue(requests.openOf(USER).isPresent()),
                () -> assertTrue(requests.openOf(OTHER).isPresent()),
                () -> assertEquals(2, requests.allOpen().size()));
    }

    @Test
    void theReferenceMatchesThePatternTheFallbackMatcherScansFor() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(
                PaymentRequests.REFERENCE_PATTERN.matcher(request.reference()).matches(), request.reference());
    }

    // The flow.

    @Test
    void anUnconfirmedRequestIsEditedInPlaceRatherThanReplaced() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertTrue(requests.reselect(request.id(), 60, 1000, 500));

        final PaymentRequest reloaded = requests.openOf(USER).orElseThrow();
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
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        // The tab asks bunq for a fixed amount; editing the row would make the two disagree.
        assertFalse(requests.reselect(request.id(), 60, 500, 0));
    }

    @Test
    void aRequestPastItsTtlTurnsUpInTheExpirySweep() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
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

    // Booking.

    @Test
    void oneBunqPaymentCanOnlyEverBeBookedOnce() {
        final PaymentRequest first = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.settle(first.id(), 777L));
        requests.close(first.id(), PaymentRequestStatus.CANCELLED); // no-op: it is PAID

        final PaymentRequest second = requests.open(OTHER, 30, 300, 0, TTL_HOURS);

        // The partial unique index on bunq_payment_id is the only thing that stops a second match.
        assertFalse(requests.settle(second.id(), 777L));
        assertTrue(requests.alreadyBooked(777L));
    }

    @Test
    void settlingTwiceBooksOnce() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertAll(
                () -> assertTrue(requests.settle(request.id(), 888L)),
                () -> assertFalse(requests.settle(request.id(), 888L), "the row is no longer OPEN"));
    }

    @Test
    void aManualSettlementBooksWithoutABunqPaymentId() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertTrue(requests.settleManually(request.id()));

        final PaymentRequest reloaded = requests.recentOf(USER, 1).getFirst();
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
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.settle(request.id(), 999L);

        access.grantAccess(USER, 30, AccessSource.PURCHASE, request.id());

        // The second half of the double-booking guard: two code paths settling once each still cannot double-grant.
        assertThrows(RuntimeException.class, () -> access.grantAccess(USER, 30, AccessSource.PURCHASE, request.id()));
    }

    @Test
    void aDowngradedPaymentGrantsTheDaysItCoveredAppendedToRunningAccess() {
        // Ordered 90 days, only enough for 30 arrived; the grant that comes out is appended, not restarted.
        final AccessGrant first = access.grantAccess(USER, 30, AccessSource.ADMIN, null);

        final PaymentRequest request = requests.open(USER, 90, 700, 0, TTL_HOURS);
        requests.settle(request.id(), 1234L);
        final AccessGrant second = access.grantAccess(USER, 30, AccessSource.PURCHASE, request.id());

        assertAll(
                () -> assertEquals(first.validUntil(), second.validFrom(), "renewing early never loses paid time"),
                () -> assertEquals(Duration.ofDays(30), Duration.between(second.validFrom(), second.validUntil())),
                () -> assertTrue(
                        second.validUntil().isAfter(Instant.now().plus(Duration.ofDays(59))),
                        "30 days on top of 30 days"));
    }

    // The seam (concept §10d).

    @Test
    void aRequestThatWantsATabTurnsUpInTheWorkersQueueAndOnlyThen() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.tabsToCreate().isEmpty(), "choosing a tier is not asking for a payment link");

        assertTrue(requests.requestTab(request.id()));

        assertAll(
                () -> assertEquals(List.of(request.reference()), references(requests.tabsToCreate())),
                () -> assertNotNull(requests.tabsToCreate().getFirst().tabRequested()),
                () -> assertNull(requests.tabsToCreate().getFirst().tabFailed()));
    }

    @Test
    void theQueueEmptiesTheMomentTheTabExists() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());
        assertEquals(1, requests.tabsToCreate().size(), "queued");

        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        // Without this the worker makes a second tab on its next pass, and neither link is the one shown.
        assertTrue(requests.tabsToCreate().isEmpty(), "a request with a tab is not waiting for one");
        assertFalse(requests.requestTab(request.id()), "and asking again changes nothing");
    }

    @Test
    void aRefusedTabLeavesTheQueueSaysWhyAndCanBeAskedForAgain() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());

        assertTrue(requests.failTab(request.id(), "bunq: MonetaryAccount not found"));

        final PaymentRequest failed = requests.openOf(USER).orElseThrow();
        assertAll(
                () -> assertTrue(
                        requests.tabsToCreate().isEmpty(),
                        "a failure retried on every pass is a failure repeated forever"),
                () -> assertNull(failed.tabRequested()),
                () -> assertEquals(
                        "bunq: MonetaryAccount not found", failed.tabFailed(), "'der Link kommt gleich' needs an exit"),
                () -> assertTrue(requests.requestTab(request.id())),
                () -> assertNull(
                        requests.openOf(USER).orElseThrow().tabFailed(),
                        "asking again clears the old reason rather than showing it next to a pending ask"));
    }

    @Test
    void aRequestAlreadyAskedToBeCancelledIsNeverGivenATab() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());
        assertTrue(requests.requestCancel(request.id()));

        // Otherwise the window before the status is written produces a tab whose only purpose is to be cancelled.
        assertTrue(requests.tabsToCreate().isEmpty());
    }

    @Test
    void aCancelIsQueuedOnceAndLeavesTheQueueWhenTheTabIsGone() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
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
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");

        assertTrue(requests.recordMatch(request.id(), 4711L, 300, PaymentMatch.TAB));

        final PaymentRequest matched = requests.openOf(USER).orElseThrow();
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
        final PaymentRequest first = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.recordMatch(first.id(), 4711L, 300, PaymentMatch.TAB));

        final PaymentRequest second = requests.open(OTHER, 30, 300, 0, TTL_HOURS);

        // The same partial unique index that guards settle(); recordMatch passes the failure on rather than hiding it.
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

    // The watermark.

    @Test
    void theFirstStartStampsTheWatermarkAndNoLaterStartMovesIt() throws Exception {
        final Instant before = Instant.now();
        final Instant first = Watermark.resolve(database.jdbi(), "");

        // Long enough that a second "now" would be a different instant if anything rewrote it.
        Thread.sleep(50);
        final Instant second = Watermark.resolve(database.jdbi(), "");

        assertAll(
                () -> assertFalse(first.isBefore(before.minusSeconds(1))),
                () -> assertEquals(
                        first,
                        second,
                        "a restart must not move the cut-off forward - everything between the two "
                                + "would be ignored forever"),
                () -> assertTrue(Watermark.storedAt(database.jdbi()).isPresent()));
    }

    @Test
    void anOverrideWinsButDoesNotReplaceTheStoredValue() {
        final Instant stored = Watermark.resolve(database.jdbi(), "");

        final Instant overridden = Watermark.resolve(database.jdbi(), "2020-01-01T00:00:00Z");
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), overridden);

        // Emptying the override again falls back to the original first-start instant, not the restart moment.
        assertEquals(stored, Watermark.resolve(database.jdbi(), ""));
    }

    @Test
    void theWatermarkExistsBeforeTheFirstPollEvenWhenAnOverrideIsSet() {
        // Otherwise removing the override on a long-running bot would set the cut-off to that restart.
        Watermark.resolve(database.jdbi(), "2020-01-01T00:00:00Z");

        assertTrue(Watermark.storedAt(database.jdbi()).isPresent());
    }

    // Admin notices.

    @Test
    void aPaymentIsRaisedToTheAdminChannelExactlyOnceHoweverOftenItIsPolled() {
        // Without this the poll loop repeats the same line every interval, forever, since bunq keeps returning it.
        assertAll(
                () -> assertTrue(requests.noticeOnce(555L, "UNMATCHED", "first")),
                () -> assertFalse(requests.noticeOnce(555L, "UNMATCHED", "second poll")),
                () -> assertFalse(requests.noticeOnce(555L, "UNMATCHED", "third poll")));
    }

    @Test
    void aNoticeWaitsInTheTableUntilSomebodyClaimsItAndIsClaimedOnlyOnce() {
        // The `posted` column makes the row a queue, so a bot that dies between write and post still has it.
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

        // Written by one container, read by another - a gone writer process is the normal case here.
        assertEquals(1, requests.unpostedNotices().size());
        assertEquals(1, requests.unpostedNotices().size(), "reading is not claiming");
    }

    // The bot's queue.

    @Test
    void matchedMoneyWaitsInAQueueOfItsOwnUntilItIsBooked() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");
        assertTrue(requests.matchedAwaitingBooking().isEmpty(), "an open request with a tab is not money");

        assertTrue(requests.recordMatch(request.id(), 4711L, 500, PaymentMatch.REFERENCE));

        final List<PaymentRequest> queue = requests.matchedAwaitingBooking();
        assertEquals(List.of(request.reference()), references(queue));
        assertAll(
                // matched_cents, not bunq_payment_id: what is granted comes from what arrived, and this has none.
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
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

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
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
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
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        // PAID means money arrived and something has to be granted for it, not a row closed silently with none.
        assertThrows(
                IllegalArgumentException.class,
                () -> requests.closeAndRequestCancel(request.id(), PaymentRequestStatus.PAID));
        assertEquals(
                PaymentRequestStatus.OPEN,
                requests.byId(request.id()).orElseThrow().status());
    }

    @Test
    void byIdAnswersTheOneRowTheWaitingPurchaseMessageIsAbout() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertEquals(
                request.reference(), requests.byId(request.id()).orElseThrow().reference());
        assertTrue(requests.byId(java.util.UUID.randomUUID()).isEmpty());
    }
}
