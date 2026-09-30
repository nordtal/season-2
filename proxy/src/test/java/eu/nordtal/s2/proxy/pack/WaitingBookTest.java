package eu.nordtal.s2.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.limboprotocol.WaitReason;
import eu.nordtal.s2.proxy.MutableClock;
import eu.nordtal.s2.proxy.ProxyRole;
import eu.nordtal.s2.proxy.pack.WaitingDecision.Action;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The waiting room's release rule, asserted in every order the three facts can arrive in.
 *
 * Velocity decides the order, so every order must produce the same answer.
 */
class WaitingBookTest {

    private static final Duration APPLY_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration READY_GRACE = Duration.ofSeconds(5);
    private static final SeasonPhase PLAYABLE = SeasonPhase.PRE_EVENT;
    /** The phase's backend; a retry window belongs to a backend, not to a player. */
    private static final String DESTINATION = "hunger-games";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:09:58Z"));
    private final UUID player = UUID.randomUUID();

    private WaitingBook book() {
        return new WaitingBook(true, APPLY_TIMEOUT, READY_GRACE, ProxyRole.LIVE, clock);
    }

    private Action decide(final WaitingBook book) {
        return book.decide(player, PLAYABLE, false, true, DESTINATION, false, false)
                .action();
    }

    // READY before the arrival

    @Test
    void readyBeforeArrivalIsRemembered() {
        final WaitingBook book = book();

        // Velocity resumes reading the backend before dispatching ServerPostConnectEvent, so READY arrives first.
        assertTrue(book.ready(player), "a READY before the arrival must report itself as early");
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);

        assertEquals(Action.RELEASE, decide(book), "the player must leave the waiting room, not sit in it for ever");
    }

    @Test
    void aLateReadyIsNotEarly() {
        // limbo repeats READY every second; a repeat after release must not log the early line.
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        assertFalse(book.ready(player), "a READY during the visit is on time");
        assertEquals(Action.RELEASE, decide(book));
        assertFalse(book.ready(player), "a READY after the release is late, not early");
        assertEquals(Action.IDLE, decide(book), "and it does not re-open the visit");
    }

    @Test
    void everyOrderReleases() {
        final Consumer<WaitingBook> arrive = b -> b.entered(player);
        final Consumer<WaitingBook> pack = b -> b.packApplied(player);
        final Consumer<WaitingBook> ready = b -> b.ready(player);

        final List<List<Consumer<WaitingBook>>> orders = List.of(
                List.of(arrive, pack, ready),
                List.of(arrive, ready, pack),
                List.of(pack, arrive, ready),
                List.of(pack, ready, arrive),
                List.of(ready, arrive, pack),
                List.of(ready, pack, arrive));

        final List<Action> outcomes = new ArrayList<>();
        for (final List<Consumer<WaitingBook>> order : orders) {
            final WaitingBook book = book();
            order.forEach(step -> step.accept(book));
            outcomes.add(decide(book));
        }

        assertEquals(
                List.of(Action.RELEASE, Action.RELEASE, Action.RELEASE, Action.RELEASE, Action.RELEASE, Action.RELEASE),
                outcomes,
                "the release must not depend on which event Velocity dispatched first");
    }

    // the exemption an admin sets in Steward

    @Test
    void anExemptPlayerIsReleasedWithoutAnyPack() {
        final WaitingBook book = book();
        book.entered(player);
        assertTrue(book.packExempt(player), "the first pass of a login is the one that is logged");
        assertFalse(book.packExempt(player), "and it is logged once, not on every re-check");
        assertFalse(book.claimOffer(player), "an exempt player was sent the pack anyway");
        book.ready(player);
        assertEquals(Action.RELEASE, decide(book), "an exempt player waited for a pack nobody sent them");
    }

    @Test
    void anExemptPlayerIsNeverTimedOutForAPackTheyWereNotSent() {
        final WaitingBook book = book();
        book.entered(player);
        book.packExempt(player);
        clock.advance(APPLY_TIMEOUT.plusMinutes(1));
        book.ready(player);
        assertEquals(Action.RELEASE, decide(book));
    }

    @Test
    void withoutTheExemptionThePackIsStillWaitedFor() {
        final WaitingBook book = book();
        book.entered(player);
        assertTrue(book.claimOffer(player));
        book.ready(player);
        assertEquals(Action.SHOW, decide(book), "a player nobody exempted was let through without the pack");
    }

    @Test
    void readyAfterArrivalIsOrdinary() {
        final WaitingBook book = book();
        book.entered(player);
        assertFalse(book.ready(player), "the ordinary order must not log the warning that names the race");
    }

    // the grace period

    @Test
    void aLostReadyIsSurvivable() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);

        assertEquals(Action.IDLE, decide(book), "the first look only starts the clock");
        clock.advance(READY_GRACE.minusSeconds(1));
        assertEquals(Action.IDLE, decide(book), "one second short of the grace is still waiting");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(
                Action.RELEASE_UNCONFIRMED, decide(book), "no single message may be able to strand a player for ever");
    }

    @Test
    void aLateReadyStillCounts() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        assertEquals(Action.IDLE, decide(book));

        clock.advance(Duration.ofSeconds(2));
        book.ready(player);

        assertEquals(
                Action.RELEASE,
                decide(book),
                "a READY that arrives late is still a READY, and must not be reported as missing");
    }

    @Test
    void aNewReasonRestartsTheGrace() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        assertEquals(Action.IDLE, decide(book), "the clock starts here");

        clock.advance(Duration.ofSeconds(4));
        // The phase's backend goes away: the wait is no longer down to READY alone.
        assertEquals(
                Action.SHOW,
                book.decide(player, PLAYABLE, false, false, DESTINATION, false, false)
                        .action());
        clock.advance(Duration.ofSeconds(4));

        // Eight seconds passed total, more than the grace, but the wait just came down to READY again.
        assertEquals(
                Action.IDLE, decide(book), "a release without READY must measure the wait it is actually excusing");
    }

    // releasing exactly once

    @Test
    void aPlayerIsReleasedOnce() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        book.ready(player);

        assertEquals(Action.RELEASE, decide(book));
        assertEquals(
                Action.IDLE,
                decide(book),
                "the sweep and a pack status arrive together often enough; a player must not be "
                        + "connected onward twice");
        assertFalse(book.isWaiting(player), "a released player is no longer held");
    }

    @Test
    void aPlayerIsTimedOutOnce() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        clock.advance(APPLY_TIMEOUT);

        assertEquals(Action.TIMED_OUT, decide(book));
        assertEquals(Action.IDLE, decide(book));
    }

    // the title

    @Test
    void theSameTitleIsSentOnce() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);

        assertEquals(
                WaitingDecision.show(WaitReason.PACK),
                book.decide(player, PLAYABLE, false, true, DESTINATION, false, false),
                "the first look has to tell limbo what to draw");
        assertEquals(
                Action.IDLE,
                decide(book),
                "the sweep runs every few seconds; re-sending would re-issue the title on a loop");
    }

    @Test
    void aChangedTitleIsSent() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        assertEquals(Action.SHOW, decide(book));

        book.packApplied(player);
        assertEquals(
                WaitingDecision.show(WaitReason.MAINTENANCE),
                book.decide(player, SeasonPhase.MAINTENANCE, false, true, DESTINATION, false, false));
    }

    @Test
    void leavingKeepsWhatIsTrueOfTheSession() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        assertEquals(Action.SHOW, decide(book));

        book.left(player);
        assertFalse(book.isWaiting(player));
        assertEquals(Action.IDLE, decide(book), "nothing is decided about a player who is not held");

        book.entered(player);
        assertFalse(
                book.claimOffer(player), "a player bounced back into the waiting room is not asked for the pack twice");
        assertEquals(
                Action.SHOW, decide(book), "the second visit has to redraw: limbo shows whatever it was last told");
    }

    // the pack switch

    @Test
    void aDisabledPackShortensTheWait() {
        final WaitingBook book = new WaitingBook(false, APPLY_TIMEOUT, READY_GRACE, ProxyRole.LIVE, clock);
        book.entered(player);
        book.ready(player);
        clock.advance(APPLY_TIMEOUT.multipliedBy(10));

        assertEquals(
                Action.RELEASE,
                decide(book),
                "pack.yml#enabled false is a waiting room with one fewer thing in it, not a "
                        + "waiting room that disconnects everybody");
    }

    @Test
    void theTimeoutOnlyAppliesToAnUnansweredOffer() {
        final WaitingBook timing = book();
        timing.entered(player);
        timing.claimOffer(player);
        clock.advance(APPLY_TIMEOUT);
        assertEquals(Action.TIMED_OUT, decide(timing));

        final MutableClock second = new MutableClock(Instant.parse("2026-09-03T00:09:58Z"));
        final WaitingBook applied = new WaitingBook(true, APPLY_TIMEOUT, READY_GRACE, ProxyRole.LIVE, second);
        applied.entered(player);
        applied.claimOffer(player);
        applied.packApplied(player);
        applied.ready(player);
        second.advance(APPLY_TIMEOUT.multipliedBy(10));
        assertEquals(
                Action.RELEASE,
                applied.decide(player, PLAYABLE, false, true, DESTINATION, false, false)
                        .action(),
                "the clock only runs against a client that never answered at all");
    }

    @Test
    void anAdminLeavesDuringMaintenance() {
        // An admin must not be held under maintenance or released back into the room they stand in.
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        book.ready(player);

        assertEquals(
                WaitingDecision.show(WaitReason.BACKEND),
                book.decide(player, SeasonPhase.MAINTENANCE, true, false, DESTINATION, false, false),
                "the SMP is not registered: the admin waits for it, and is told that");
        assertEquals(
                Action.RELEASE,
                book.decide(player, SeasonPhase.MAINTENANCE, true, true, DESTINATION, false, false)
                        .action(),
                "the SMP is there: nothing is left to wait for");
    }

    @Test
    void aNonAdminStaysDuringMaintenance() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        book.ready(player);

        assertEquals(
                WaitingDecision.show(WaitReason.MAINTENANCE),
                book.decide(player, SeasonPhase.MAINTENANCE, false, true, DESTINATION, false, false));
    }

    @Test
    void aFailedReleaseHoldsAndRetries() {
        // A registered backend that does not answer must hold the player, not release them into a failed connection.
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        book.ready(player);
        assertEquals(Action.RELEASE, decide(book));

        book.releaseFailed(player, DESTINATION);
        assertEquals(
                WaitingDecision.show(WaitReason.BACKEND),
                book.decide(player, PLAYABLE, false, true, DESTINATION, false, false),
                "back on the books, and told what they are waiting for");
        clock.advance(WaitingBook.RELEASE_RETRY.dividedBy(2));
        assertEquals(Action.IDLE, decide(book), "not retried before the window has passed");
        clock.advance(WaitingBook.RELEASE_RETRY);
        assertEquals(Action.RELEASE, decide(book), "and tried again once it has");
        assertEquals(Action.IDLE, decide(book), "a release still ends the visit, so it is not sent twice");
    }

    @Test
    void aRetryWindowBelongsToTheBackendThatRefused() {
        // The window says the server did not take them, not that this player waits.
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        book.ready(player);
        assertEquals(Action.RELEASE, decide(book));

        book.releaseFailed(player, DESTINATION);
        assertEquals(
                WaitingDecision.show(WaitReason.BACKEND),
                book.decide(player, PLAYABLE, false, true, DESTINATION, false, false),
                "the backend that refused is still refused");
        assertEquals(
                Action.RELEASE,
                book.decide(player, SeasonPhase.SMP, false, true, "smp", false, false)
                        .action(),
                "another backend is not held for a failure that was not its own");
    }

    // housekeeping

    @Test
    void anUnknownPlayerIsIdle() {
        assertEquals(Action.IDLE, decide(book()));
    }

    @Test
    void disconnectingClearsTheSession() {
        final WaitingBook book = book();
        book.entered(player);
        book.packApplied(player);
        assertEquals(1, book.size());

        book.forget(player);
        assertEquals(0, book.size(), "the map would otherwise grow for the life of the process");
        assertFalse(book.isWaiting(player));
    }
}
