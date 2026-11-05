package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.proxy.NetworkServers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The way back, and who is told about it: nobody just logged in, nobody waiting, and never without warning. */
class HomecomingTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Homecoming.Owed OFF_SMP = new Homecoming.Owed("smp", false);

    private final Homecoming homecoming = new Homecoming(
            org.slf4j.LoggerFactory.getLogger(HomecomingTest.class),
            eu.nordtal.season.proxy.ProxyRenderer.RENDERER,
            new eu.nordtal.season.proxy.gate.LoginRoster(),
            SERVERS,
            eu.nordtal.season.database.update.RunRecorder.NONE);

    // who is owed a sentence

    @Test
    void theRegisterIsTheEvacuationAndNotEveryRelease() {
        final UUID moved = UUID.randomUUID();
        final UUID justLoggedIn = UUID.randomUUID();
        homecoming.owe(1L, moved, OFF_SMP);

        // Every login waits in limbo, so a release alone is not a homecoming.
        assertNull(homecoming.claim(justLoggedIn));

        assertNotNull(homecoming.claim(moved));
        assertNull(
                homecoming.claim(moved),
                "a release that failed and is retried every ten seconds must not say it again");
    }

    @Test
    void aNewRunReplacesTheRegister() {
        final UUID lastRun = UUID.randomUUID();
        final UUID thisRun = UUID.randomUUID();
        homecoming.owe(1L, lastRun, OFF_SMP);
        homecoming.owe(2L, thisRun, OFF_SMP);

        assertNull(
                homecoming.claim(lastRun),
                "somebody who never came back from the last run is not owed a line about this one");
        assertNotNull(homecoming.claim(thisRun));
    }

    @Test
    void theSameRunAddsToTheRegister() {
        final UUID first = UUID.randomUUID();
        final UUID later = UUID.randomUUID();

        assertTrue(homecoming.owe(1L, first, OFF_SMP));
        assertTrue(homecoming.owe(1L, later, OFF_SMP));
        assertFalse(homecoming.owe(1L, first, OFF_SMP), "a later pass of the same run owes nobody twice");

        // A player who reached the waiting room on an earlier pass is still told on return.
        assertNotNull(homecoming.claim(first));
        assertNotNull(homecoming.claim(later));
    }

    @Test
    void aClaimKeepsTheServerMovedOffAndWhetherASwitchMovedThem() {
        // A mode switch returns the player elsewhere, yet the return counts against the move off that server.
        final UUID player = UUID.randomUUID();
        homecoming.owe(3L, player, new Homecoming.Owed("hunger-games", true));

        assertEquals(new Homecoming.Owed("hunger-games", true), homecoming.claim(player));
    }

    // who gets a counter

    @Test
    void onlyAnInterruptedGameIsCountedDown() {
        assertTrue(Homecoming.interrupts("smp", SERVERS));
        assertTrue(Homecoming.interrupts("hunger-games", SERVERS));

        // Counting at somebody on a "please wait" screen only delays the thing ending it.
        assertFalse(Homecoming.interrupts("limbo", SERVERS));
        assertFalse(Homecoming.interrupts("limbo-standby", SERVERS));
        assertFalse(Homecoming.interrupts(null, SERVERS), "still connecting; there is no game yet");
    }

    // the notice itself

    @Test
    void theNoticeIsCountedAllTheWay() {
        final List<CountdownPlan.Beat<Announcement>> beats =
                new Countdown().beats(1L, Homecoming.NOTICE).orElseThrow();

        // One chat line at ten, nine subtitles, the transfer; the ten-second tick is dropped since chat draws it.
        assertEquals(Announcement.Kind.COUNTDOWN, beats.get(0).said().kind());
        assertEquals(10L, beats.get(0).said().seconds());
        assertEquals(java.time.Duration.ZERO, beats.get(0).delay(), "the first word is said now");
        assertEquals(11, beats.size(), beats.toString());
        assertEquals(Announcement.Kind.NOW, beats.get(beats.size() - 1).said().kind());
        assertEquals(
                Homecoming.NOTICE,
                beats.get(beats.size() - 1).delay(),
                "the transfer is the zero beat and nothing else moves anybody");

        // A notice longer than Countdown's subtitle stretch leaves silence, then a counter mid-wait.
        assertTrue(
                Homecoming.NOTICE.toSeconds() <= Countdown.SUBTITLES_FROM,
                "the notice has grown past the seconds Countdown counts");
    }

    // the lines
}
