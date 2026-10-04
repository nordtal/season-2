package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.proxy.PhaseServers;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The way back, and who is told about it: nobody just logged in, nobody waiting, and never without warning. */
class HomecomingTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final Homecoming homecoming = new Homecoming(
            org.slf4j.LoggerFactory.getLogger(HomecomingTest.class),
            MessageRenderer.of(eu.nordtal.s2.messages.Messages.load(
                    HomecomingTest.class.getClassLoader(),
                    "messages/proxy",
                    java.util.Locale.ENGLISH,
                    java.util.Locale.GERMAN)),
            new eu.nordtal.s2.proxy.gate.LoginRoster(),
            SERVERS);

    // who is owed a sentence

    @Test
    void theRegisterIsTheEvacuationAndNotEveryRelease() {
        final UUID moved = UUID.randomUUID();
        final UUID justLoggedIn = UUID.randomUUID();
        homecoming.owedNow(Set.of(moved));

        // Every login waits in limbo, so a release alone is not a homecoming.
        assertFalse(homecoming.claim(justLoggedIn));

        assertTrue(homecoming.claim(moved));
        assertFalse(
                homecoming.claim(moved),
                "a release that failed and is retried every ten seconds must not say it again");
    }

    @Test
    void theRegisterIsReplacedRatherThanAddedTo() {
        final UUID lastRun = UUID.randomUUID();
        final UUID thisRun = UUID.randomUUID();
        homecoming.owedNow(Set.of(lastRun));
        homecoming.owedNow(Set.of(thisRun));

        assertFalse(
                homecoming.claim(lastRun),
                "somebody who never came back from the last run is not owed a line about this one");
        assertTrue(homecoming.claim(thisRun));
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
        final List<Countdown.Beat> beats =
                new Countdown().beats(1L, Homecoming.NOTICE).orElseThrow();

        // One chat line at ten, nine subtitles, the transfer; the ten-second tick is dropped since chat draws it.
        assertEquals(Announcement.Kind.COUNTDOWN, beats.get(0).announcement().kind());
        assertEquals(10L, beats.get(0).announcement().seconds());
        assertEquals(java.time.Duration.ZERO, beats.get(0).delay(), "the first word is said now");
        assertEquals(11, beats.size(), beats.toString());
        assertEquals(
                Announcement.Kind.NOW,
                beats.get(beats.size() - 1).announcement().kind());
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
