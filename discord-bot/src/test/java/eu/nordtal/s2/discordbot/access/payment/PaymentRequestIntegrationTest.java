package eu.nordtal.s2.discordbot.access.payment;

import eu.nordtal.s2.common.payment.PaymentMatch;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.common.payment.Watermark;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;

import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The payment request state machine against a real PostgreSQL, running the real migrations.
 * <p>
 * An in-memory stand-in would prove nothing here: every rule this exercises is a constraint or an
 * index in the schema - one open request per person, one grant per request, one booking per bunq
 * payment - and the reference retry only matters because a unique violation is a real error with a
 * real SQLSTATE.
 * </p>
 * <p>
 * Testcontainers is driven by hand from {@link BeforeAll} rather than through the
 * {@code @Testcontainers} extension: that extension ships in {@code org.testcontainers:junit-jupiter},
 * which is built against JUnit 5, and this repo is on the JUnit 6 BOM. The test skips itself when
 * no Docker daemon is reachable - so a green build on a machine without Docker proves less than it
 * looks.
 * </p>
 * <p>
 * What this <b>cannot</b> prove: anything about bunq or about Discord. Tab creation, tab
 * cancellation and result inquiries need the bunq sandbox; buttons, modals, ephemeral messages and
 * role assignment need a real guild.
 * </p>
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        database = Database.create(DatabaseConfig.of(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
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
        database.jdbi().useHandle(handle -> handle.execute(
                "TRUNCATE access_grant, payment_request, expiry_notice, payment_notice, "
                        + "account_link, link_code, audit_log, discord_user CASCADE"));
        requests = new PaymentRequests(database.jdbi());
        access = AccessDirectory.using(database.dataSource());
    }

    // ---------------------------------------------------------------- the schema's own rules

    @Test
    @DisplayName("the migration applies and creates the stage B tables too")
    void migrationCreatesEverything() {
        final List<String> tables = database.jdbi().withHandle(handle -> handle
                .createQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
                .mapTo(String.class)
                .list());

        assertTrue(tables.containsAll(List.of(
                        "access_grant", "account_link", "audit_log", "bot_setting", "discord_user",
                        "expiry_notice", "link_code", "managed_message", "payment_notice",
                        "payment_request")),
                tables.toString());
    }

    @Test
    @DisplayName("a second open request for the same person is refused by the database")
    void onlyOneOpenRequestPerPerson() {
        requests.open(USER, 30, 300, 0, TTL_HOURS);

        // Not a check in Java that two threads could both pass - a partial unique index.
        assertThrows(UnableToExecuteStatementException.class,
                () -> requests.open(USER, 60, 500, 0, TTL_HOURS));
    }

    @Test
    @DisplayName("closing the old request is what makes a new one possible")
    void supersedingFreesTheSlot() {
        final PaymentRequest first = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.close(first.id(), PaymentRequestStatus.SUPERSEDED));

        final PaymentRequest second = requests.open(USER, 60, 500, 0, TTL_HOURS);

        assertAll(
                () -> assertEquals(60, second.days()),
                () -> assertEquals(Optional.of(second.id()), requests.openOf(USER).map(PaymentRequest::id)),
                () -> assertFalse(first.reference().equals(second.reference()),
                        "each request gets its own reference")
        );
    }

    @Test
    @DisplayName("two people can each have an open request")
    void oneOpenRequestIsPerPerson() {
        requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.open(OTHER, 30, 300, 0, TTL_HOURS);

        assertAll(
                () -> assertTrue(requests.openOf(USER).isPresent()),
                () -> assertTrue(requests.openOf(OTHER).isPresent()),
                () -> assertEquals(2, requests.allOpen().size())
        );
    }

    @Test
    @DisplayName("the reference matches the pattern the fallback matcher scans for")
    void referenceMatchesThePattern() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(PaymentRequests.REFERENCE_PATTERN.matcher(request.reference()).matches(),
                request.reference());
    }

    // ---------------------------------------------------------------- the flow

    @Test
    @DisplayName("an unconfirmed request is edited in place rather than replaced")
    void reselectEditsInPlace() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertTrue(requests.reselect(request.id(), 60, 1000, 500));

        final PaymentRequest reloaded = requests.openOf(USER).orElseThrow();
        assertAll(
                () -> assertEquals(request.reference(), reloaded.reference(),
                        "clicking through the options must not burn a reference per click"),
                () -> assertEquals(60, reloaded.days()),
                () -> assertEquals(1000, reloaded.amountCents()),
                () -> assertTrue(reloaded.donationRequested())
        );
    }

    @Test
    @DisplayName("once a tab exists the request can no longer be edited")
    void reselectRefusedAfterATabExists() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        // The tab asks bunq for a fixed amount; editing the row would make the two disagree.
        assertFalse(requests.reselect(request.id(), 60, 500, 0));
    }

    @Test
    @DisplayName("a request past its TTL turns up in the expiry sweep")
    void expirySweepFindsOverdueRequests() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.dueForExpiry().isEmpty(), "not due yet");

        database.jdbi().useHandle(handle -> handle
                .createUpdate("UPDATE payment_request SET expires = now() - interval '1 minute' WHERE id = :id")
                .bind("id", request.id())
                .execute());

        assertEquals(List.of(request.reference()),
                requests.dueForExpiry().stream().map(PaymentRequest::reference).toList());
    }

    // ---------------------------------------------------------------- booking

    @Test
    @DisplayName("one bunq payment can only ever be booked once")
    void aPaymentCannotBeBookedTwice() {
        final PaymentRequest first = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.settle(first.id(), 777L));
        requests.close(first.id(), PaymentRequestStatus.CANCELLED); // no-op: it is PAID

        final PaymentRequest second = requests.open(OTHER, 30, 300, 0, TTL_HOURS);

        // The same payment matched to a second request. The partial unique index on
        // bunq_payment_id is the only thing that stops this - the poll loop's "have I seen it"
        // check is read-then-write and two overlapping polls would both pass it.
        assertFalse(requests.settle(second.id(), 777L));
        assertTrue(requests.alreadyBooked(777L));
    }

    @Test
    @DisplayName("settling twice books once")
    void settlingTwiceBooksOnce() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertAll(
                () -> assertTrue(requests.settle(request.id(), 888L)),
                () -> assertFalse(requests.settle(request.id(), 888L), "the row is no longer OPEN")
        );
    }

    @Test
    @DisplayName("a manual settlement books without a bunq payment id")
    void manualSettlement() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);

        assertTrue(requests.settleManually(request.id()));

        final PaymentRequest reloaded = requests.recentOf(USER, 1).getFirst();
        assertAll(
                () -> assertEquals(PaymentRequestStatus.PAID, reloaded.status()),
                () -> assertNotNull(reloaded.settled()),
                () -> assertEquals(null, reloaded.bunqPaymentId(),
                        "a manual settlement is told apart from a matched one by having no payment")
        );
    }

    @Test
    @DisplayName("one request can only ever produce one grant")
    void oneGrantPerRequest() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.settle(request.id(), 999L);

        access.grantAccess(USER, 30, AccessSource.PURCHASE, request.id());

        // The second half of the double-booking guard: even a request settled twice through two
        // different code paths cannot hand out two periods.
        assertThrows(RuntimeException.class,
                () -> access.grantAccess(USER, 30, AccessSource.PURCHASE, request.id()));
    }

    @Test
    @DisplayName("a downgraded payment grants the days it covered, appended to running access")
    void downgradeAppendsTheDaysActuallyCovered() {
        // Ordered 90 days; only enough for 30 arrived. The rule lives in Tiers; what this proves
        // is that the grant that comes out of it is 30 days and is appended, not restarted.
        final AccessGrant first = access.grantAccess(USER, 30, AccessSource.ADMIN, null);

        final PaymentRequest request = requests.open(USER, 90, 700, 0, TTL_HOURS);
        requests.settle(request.id(), 1234L);
        final AccessGrant second = access.grantAccess(USER, 30, AccessSource.PURCHASE, request.id());

        assertAll(
                () -> assertEquals(first.validUntil(), second.validFrom(),
                        "renewing early never loses paid time"),
                () -> assertEquals(Duration.ofDays(30), Duration.between(second.validFrom(), second.validUntil())),
                () -> assertTrue(second.validUntil().isAfter(Instant.now().plus(Duration.ofDays(59))),
                        "30 days on top of 30 days")
        );
    }

    // ---------------------------------------------------------------- the seam (concept §10d)

    @Test
    @DisplayName("a request that wants a tab turns up in the worker's queue, and only then")
    void requestedTabTurnsUpInTheQueue() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.tabsToCreate().isEmpty(),
                "choosing a tier is not asking for a payment link");

        assertTrue(requests.requestTab(request.id()));

        assertAll(
                () -> assertEquals(List.of(request.reference()), references(requests.tabsToCreate())),
                () -> assertNotNull(requests.tabsToCreate().getFirst().tabRequested()),
                () -> assertNull(requests.tabsToCreate().getFirst().tabFailed())
        );
    }

    @Test
    @DisplayName("the queue empties the moment the tab exists")
    void theQueueEmptiesWhenTheTabArrives() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());
        assertEquals(1, requests.tabsToCreate().size(), "queued");

        assertTrue(requests.attachTab(request.id(), 4242L, "https://bunq.me/x"));

        // Without this the worker makes a second tab for the same request on its next pass, and
        // the bot has no way to tell which of the two links the user is looking at.
        assertTrue(requests.tabsToCreate().isEmpty(), "a request with a tab is not waiting for one");
        assertFalse(requests.requestTab(request.id()), "and asking again changes nothing");
    }

    @Test
    @DisplayName("a refused tab leaves the queue, says why, and can be asked for again")
    void aFailedTabHasAnExit() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());

        assertTrue(requests.failTab(request.id(), "bunq: MonetaryAccount not found"));

        final PaymentRequest failed = requests.openOf(USER).orElseThrow();
        assertAll(
                () -> assertTrue(requests.tabsToCreate().isEmpty(),
                        "a failure retried on every pass is a failure repeated forever"),
                () -> assertNull(failed.tabRequested()),
                () -> assertEquals("bunq: MonetaryAccount not found", failed.tabFailed(),
                        "'der Link kommt gleich' needs an exit - steward/07"),
                () -> assertTrue(requests.requestTab(request.id())),
                () -> assertNull(requests.openOf(USER).orElseThrow().tabFailed(),
                        "asking again clears the old reason rather than showing it next to a pending ask")
        );
    }

    @Test
    @DisplayName("a request already asked to be cancelled is never given a tab")
    void aCancelledRequestIsNotGivenATab() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.requestTab(request.id());
        assertTrue(requests.requestCancel(request.id()));

        // Otherwise the window between "cancel asked for" and the status being written produces a
        // tab whose only purpose is to be cancelled by the next pass.
        assertTrue(requests.tabsToCreate().isEmpty());
    }

    @Test
    @DisplayName("a cancel is queued once and leaves the queue when the tab is gone")
    void cancellingQueuesUntilTheTabIsGone() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");
        assertTrue(requests.tabsToCancel().isEmpty());

        assertTrue(requests.requestCancel(request.id()));
        assertFalse(requests.requestCancel(request.id()), "asking twice does not move the timestamp");
        assertTrue(requests.close(request.id(), PaymentRequestStatus.CANCELLED));

        assertEquals(List.of(request.reference()), references(requests.tabsToCancel()),
                "closing the row is not cancelling the tab at bunq");

        assertTrue(requests.recordCancelled(request.id()));
        assertTrue(requests.tabsToCancel().isEmpty(),
                "without an exit the worker cancels the same tab on every pass, forever");
    }

    @Test
    @DisplayName("a match is written onto a row that is still open, so the settled-iff-paid check holds")
    void aMatchDoesNotBookAnything() {
        final PaymentRequest request = requests.open(USER, 30, 300, 0, TTL_HOURS);
        requests.attachTab(request.id(), 4242L, "https://bunq.me/x");

        assertTrue(requests.recordMatch(request.id(), 4711L, 300, PaymentMatch.TAB));

        final PaymentRequest matched = requests.openOf(USER).orElseThrow();
        assertAll(
                () -> assertEquals(PaymentRequestStatus.OPEN, matched.status(),
                        "the worker finds the money; the bot books it"),
                () -> assertNull(matched.settled()),
                () -> assertEquals(300, matched.matchedCents()),
                () -> assertEquals(PaymentMatch.TAB, matched.matchedBy()),
                () -> assertEquals(4711L, matched.bunqPaymentId()),
                () -> assertTrue(requests.alreadyBooked(4711L),
                        "the claim on the payment id happens here, not at the booking")
        );
    }

    @Test
    @DisplayName("one bunq payment cannot be attributed to two requests")
    void aPaymentCannotBeClaimedTwice() {
        final PaymentRequest first = requests.open(USER, 30, 300, 0, TTL_HOURS);
        assertTrue(requests.recordMatch(first.id(), 4711L, 300, PaymentMatch.TAB));

        final PaymentRequest second = requests.open(OTHER, 30, 300, 0, TTL_HOURS);

        // Not a check in Java the worker could forget: the same partial unique index that already
        // guards settle(). recordMatch passes it on rather than absorbing it, because attribution
        // has one writer and a second claim is a bug in it.
        final UnableToExecuteStatementException failure = assertThrows(
                UnableToExecuteStatementException.class,
                () -> requests.recordMatch(second.id(), 4711L, 300, PaymentMatch.REFERENCE));
        assertTrue(String.valueOf(failure.getMessage()).contains("payment_request_bunq_payment_id_key"),
                failure.getMessage());
    }

    private static List<String> references(final List<PaymentRequest> found) {
        return found.stream().map(PaymentRequest::reference).toList();
    }

    // ---------------------------------------------------------------- the watermark

    @Test
    @DisplayName("the first start stamps the watermark, and no later start moves it")
    void watermarkIsWrittenOnce() throws Exception {
        final Instant before = Instant.now();
        final Instant first = Watermark.resolve(database.jdbi(), "");

        // Long enough that a second "now" would be a different instant if anything rewrote it.
        Thread.sleep(50);
        final Instant second = Watermark.resolve(database.jdbi(), "");

        assertAll(
                () -> assertFalse(first.isBefore(before.minusSeconds(1))),
                () -> assertEquals(first, second,
                        "a restart must not move the cut-off forward - everything between the two "
                                + "would be ignored forever"),
                () -> assertTrue(Watermark.storedAt(database.jdbi()).isPresent())
        );
    }

    @Test
    @DisplayName("an override wins but does not replace the stored value")
    void overrideDoesNotReplaceTheStoredValue() {
        final Instant stored = Watermark.resolve(database.jdbi(), "");

        final Instant overridden = Watermark.resolve(database.jdbi(), "2020-01-01T00:00:00Z");
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), overridden);

        // Emptying the override again has to fall back to the original first-start instant, not
        // to the moment somebody happened to restart the bot.
        assertEquals(stored, Watermark.resolve(database.jdbi(), ""));
    }

    @Test
    @DisplayName("the watermark exists before the first poll even when an override is set")
    void overrideStillStampsTheFirstStart() {
        // Otherwise removing the override on a bot that has run for months would set the cut-off
        // to that restart and ignore every payment before it.
        Watermark.resolve(database.jdbi(), "2020-01-01T00:00:00Z");

        assertTrue(Watermark.storedAt(database.jdbi()).isPresent());
    }

    // ---------------------------------------------------------------- admin notices

    @Test
    @DisplayName("a payment is raised to the admin channel exactly once, however often it is polled")
    void unmatchablePaymentIsRaisedOnce() {
        // Without this the poll loop would repeat the same line every interval, forever, because
        // bunq keeps returning the same payment.
        assertAll(
                () -> assertTrue(requests.noticeOnce(555L, "UNMATCHED", "first")),
                () -> assertFalse(requests.noticeOnce(555L, "UNMATCHED", "second poll")),
                () -> assertFalse(requests.noticeOnce(555L, "UNMATCHED", "third poll"))
        );
    }
}
