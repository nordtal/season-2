package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.networkcontrol.MutableClock;
import eu.nordtal.s2.networkcontrol.pack.WaitingDecision.Action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The waiting room's release rule, asserted in every order the three facts can arrive in.
 *
 * <p><b>This test exists because of finding 38</b>, and the first case below is that finding
 * exactly: {@code limbo}'s {@code READY} reaching the proxy before the arrival event did. The old
 * code dropped it, {@code limbo} never sent another, and the player sat on a black screen for the
 * rest of the session with a title that had stopped being true. Nothing logged, nothing timed out,
 * and the sweep re-asked a question whose answer could no longer change.
 *
 * <p>What makes it assertable at all is that none of it is a Velocity type. The ordering is decided
 * by Velocity's event dispatch and cannot be pinned from here - so the rule is written so that
 * <em>every</em> order produces the same answer, and that is what these cases check: not that one
 * sequence works, but that all six do.
 */
class WaitingBookTest {

    private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration READY_GRACE = Duration.ofSeconds(5);
    private static final SeasonPhase PLAYABLE = SeasonPhase.PRE_EVENT;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:09:58Z"));
    private final UUID player = UUID.randomUUID();

    private WaitingBook book() {
        return new WaitingBook(true, APPLY_TIMEOUT, READY_GRACE, clock);
    }

    private Action decide(final WaitingBook book) {
        return book.decide(player, PLAYABLE, true).action();
    }

    // ------------------------------------------------------------------ finding 38

    @Test
    @DisplayName("a READY that beats the arrival still releases the player")
    void readyBeforeArrivalIsRemembered() {
        final WaitingBook book = book();

        // The order the first deployment actually produced: Velocity resumes reading from the
        // backend before it dispatches ServerPostConnectEvent, so limbo's answer - sent one tick
        // after the join and already sitting in the socket buffer - is handled first.
        assertTrue(book.ready(player), "a READY before the arrival must report itself as early");
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);

        assertEquals(Action.RELEASE, decide(book),
                "the player must leave the waiting room, not sit in it for ever");
    }

    @Test
    @DisplayName("every order of the three facts releases the player")
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

        assertEquals(List.of(Action.RELEASE, Action.RELEASE, Action.RELEASE,
                Action.RELEASE, Action.RELEASE, Action.RELEASE), outcomes,
                "the release must not depend on which event Velocity dispatched first");
    }

    @Test
    @DisplayName("a READY after the arrival is not reported as early")
    void readyAfterArrivalIsOrdinary() {
        final WaitingBook book = book();
        book.entered(player);
        assertFalse(book.ready(player),
                "the ordinary order must not log the warning that names the race");
    }

    // ------------------------------------------------------------------ the grace period

    @Test
    @DisplayName("a lost READY delays the release by the grace period and no longer")
    void aLostReadyIsSurvivable() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);

        assertEquals(Action.IDLE, decide(book), "the first look only starts the clock");
        clock.advance(READY_GRACE.minusSeconds(1));
        assertEquals(Action.IDLE, decide(book), "one second short of the grace is still waiting");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(Action.RELEASE_UNCONFIRMED, decide(book),
                "no single message may be able to strand a player for ever");
    }

    @Test
    @DisplayName("a READY inside the grace window is a confirmed release")
    void aLateReadyStillCounts() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        assertEquals(Action.IDLE, decide(book));

        clock.advance(Duration.ofSeconds(2));
        book.ready(player);

        assertEquals(Action.RELEASE, decide(book),
                "a READY that arrives late is still a READY, and must not be reported as missing");
    }

    @Test
    @DisplayName("the grace clock restarts when something else starts blocking again")
    void aNewReasonRestartsTheGrace() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        assertEquals(Action.IDLE, decide(book), "the clock starts here");

        clock.advance(Duration.ofSeconds(4));
        // The phase's backend goes away: the wait is no longer down to READY alone.
        assertEquals(Action.SHOW, book.decide(player, PLAYABLE, false).action());
        clock.advance(Duration.ofSeconds(4));

        // Back up. Eight seconds have passed in total, which is more than the grace - but the wait
        // has only just come down to READY again, and the player has not been waiting on it.
        assertEquals(Action.IDLE, decide(book),
                "a release without READY must measure the wait it is actually excusing");
    }

    // ------------------------------------------------------------------ releasing exactly once

    @Test
    @DisplayName("only the first of two concurrent decisions releases")
    void aPlayerIsReleasedOnce() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        book.packApplied(player);
        book.ready(player);

        assertEquals(Action.RELEASE, decide(book));
        assertEquals(Action.IDLE, decide(book),
                "the sweep and a pack status arrive together often enough; a player must not be "
                        + "connected onward twice");
        assertFalse(book.isWaiting(player), "a released player is no longer held");
    }

    @Test
    @DisplayName("a timed-out player is only disconnected once")
    void aPlayerIsTimedOutOnce() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        clock.advance(APPLY_TIMEOUT);

        assertEquals(Action.TIMED_OUT, decide(book));
        assertEquals(Action.IDLE, decide(book));
    }

    // ------------------------------------------------------------------ the title

    @Test
    @DisplayName("an unchanged reason is not re-sent")
    void theSameTitleIsSentOnce() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);

        assertEquals(WaitingDecision.show(WaitReason.PACK), book.decide(player, PLAYABLE, true),
                "the first look has to tell limbo what to draw");
        assertEquals(Action.IDLE, decide(book),
                "the sweep runs every few seconds; re-sending would re-issue the title on a loop");
    }

    @Test
    @DisplayName("a changed reason is sent")
    void aChangedTitleIsSent() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        assertEquals(Action.SHOW, decide(book));

        book.packApplied(player);
        assertEquals(WaitingDecision.show(WaitReason.MAINTENANCE),
                book.decide(player, SeasonPhase.MAINTENANCE, true));
    }

    @Test
    @DisplayName("leaving the waiting room forgets the title but keeps the session's facts")
    void leavingKeepsWhatIsTrueOfTheSession() {
        final WaitingBook book = book();
        book.entered(player);
        book.claimOffer(player);
        assertEquals(Action.SHOW, decide(book));

        book.left(player);
        assertFalse(book.isWaiting(player));
        assertEquals(Action.IDLE, decide(book), "nothing is decided about a player who is not held");

        book.entered(player);
        assertFalse(book.claimOffer(player),
                "a player bounced back into the waiting room is not asked for the pack twice");
        assertEquals(Action.SHOW, decide(book),
                "the second visit has to redraw: limbo shows whatever it was last told");
    }

    // ------------------------------------------------------------------ the pack switch

    @Test
    @DisplayName("with no pack to wait for there is nothing to time out")
    void aDisabledPackShortensTheWait() {
        final WaitingBook book = new WaitingBook(false, APPLY_TIMEOUT, READY_GRACE, clock);
        book.entered(player);
        book.ready(player);
        clock.advance(APPLY_TIMEOUT.multipliedBy(10));

        assertEquals(Action.RELEASE, decide(book),
                "pack.yml#enabled false is a waiting room with one fewer thing in it, not a "
                        + "waiting room that disconnects everybody");
    }

    @Test
    @DisplayName("an unanswered offer disconnects, an answered one never does")
    void theTimeoutOnlyAppliesToAnUnansweredOffer() {
        final WaitingBook timing = book();
        timing.entered(player);
        timing.claimOffer(player);
        clock.advance(APPLY_TIMEOUT);
        assertEquals(Action.TIMED_OUT, decide(timing));

        final MutableClock second = new MutableClock(Instant.parse("2026-09-03T00:09:58Z"));
        final WaitingBook applied = new WaitingBook(true, APPLY_TIMEOUT, READY_GRACE, second);
        applied.entered(player);
        applied.claimOffer(player);
        applied.packApplied(player);
        applied.ready(player);
        second.advance(APPLY_TIMEOUT.multipliedBy(10));
        assertEquals(Action.RELEASE, applied.decide(player, PLAYABLE, true).action(),
                "the clock only runs against a client that never answered at all");
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    @DisplayName("a player nobody has heard of decides nothing")
    void anUnknownPlayerIsIdle() {
        assertEquals(Action.IDLE, decide(book()));
    }

    @Test
    @DisplayName("forgetting a player empties the book")
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
