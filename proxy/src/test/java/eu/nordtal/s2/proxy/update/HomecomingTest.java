package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.PhaseServers;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The way back, and who is told about it - season-2-ops/118, point 3.
 *
 * <p>Three rules, and each of them is a different way of being wrong in a player's chat box: a
 * sentence about a server coming back said to somebody who has just logged in, a counter shown to
 * somebody sitting in front of a "please wait" screen, and a return that happens with no warning at
 * all in the middle of a fight.</p>
 */
class HomecomingTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final Homecoming homecoming = new Homecoming(
            org.slf4j.LoggerFactory.getLogger(HomecomingTest.class),
            eu.nordtal.s2.common.message.Messages.load(
                    HomecomingTest.class.getClassLoader(),
                    "messages/proxy",
                    java.util.Locale.ENGLISH,
                    java.util.Locale.GERMAN),
            new eu.nordtal.s2.proxy.gate.LoginRoster(),
            SERVERS);

    // ------------------------------------------------------------------ who is owed a sentence

    @Test
    @DisplayName("only the players a run moved are owed a line, and each of them once")
    void theRegisterIsTheEvacuationAndNotEveryRelease() {
        final UUID moved = UUID.randomUUID();
        final UUID justLoggedIn = UUID.randomUUID();
        homecoming.owedNow(Set.of(moved));

        // The one this matters for: limbo is where EVERY login waits, so a release is not by itself
        // a homecoming. Telling somebody who has just joined that their server is back is a
        // sentence about something they never saw.
        assertFalse(homecoming.claim(justLoggedIn));

        assertTrue(homecoming.claim(moved));
        assertFalse(
                homecoming.claim(moved),
                "a release that failed and is retried every ten seconds must not say it again");
    }

    @Test
    @DisplayName("a new evacuation is the whole register, so nobody carries one over")
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

    // ------------------------------------------------------------------ who gets a counter

    @Test
    @DisplayName("the counter is for somebody playing, not for somebody already waiting")
    void onlyAnInterruptedGameIsCountedDown() {
        assertTrue(Homecoming.interrupts("smp", SERVERS));
        assertTrue(Homecoming.interrupts("hunger-games", SERVERS));

        // Both waiting rooms. Ten seconds of counting at somebody who is looking at a black screen
        // with "please wait" on it is ten more seconds of waiting, which is the thing being ended.
        assertFalse(Homecoming.interrupts("limbo", SERVERS));
        assertFalse(Homecoming.interrupts("limbo-standby", SERVERS));
        assertFalse(Homecoming.interrupts(null, SERVERS), "still connecting; there is no game yet");
    }

    // ------------------------------------------------------------------ the notice itself

    @Test
    @DisplayName("the notice is ten seconds and every one of them is counted out loud")
    void theNoticeIsCountedAllTheWay() {
        final List<Countdown.Beat> beats =
                new Countdown().beats(1L, Homecoming.NOTICE).orElseThrow();

        // One chat line at ten, nine subtitles, and the transfer. The ten-second tick is dropped
        // because the chat line of that second draws the title itself - Countdown's own rule, and
        // the reason this is asserted as a whole plan rather than as a count.
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

        // A notice longer than the stretch Countdown draws subtitles for would be silent seconds
        // at the front: a chat line, then nothing on screen, then a counter starting mid-wait.
        assertTrue(
                Homecoming.NOTICE.toSeconds() <= Countdown.SUBTITLES_FROM,
                "the notice has grown past the seconds Countdown counts");
    }

    @Test
    @DisplayName("the standby says it before it moves anybody, because a transfer ends the chat")
    void theSentenceComesBeforeTheTransfer() throws IOException {
        // A source rule, for the one thing no unit test on this side of Velocity can observe: the
        // order of two calls inside a scheduled beat. Getting it the other way round is silent -
        // the transfer succeeds, the message is written to a connection that has just gone - and
        // it is exactly the way round the countdown on the way OUT is deliberately built.
        final String source = Files.readString(Path.of("src/main/java/eu/nordtal/s2/proxy/update/StandbyReturn.java"));
        final int said = source.indexOf("voice.say(here, beat.announcement())");
        final int moved = source.indexOf("sendHome(here)");

        assertTrue(said > 0 && moved > 0, "StandbyReturn no longer speaks and transfers in one beat");
        assertTrue(said < moved, "the transfer is written before the sentence");
        assertEquals(
                said,
                source.lastIndexOf("voice.say("),
                "the standby speaks in more than one place, so one of them is after the transfer");
    }

    // ------------------------------------------------------------------ the lines

    @Test
    @DisplayName("both bundles carry the three lines the way back needs")
    void theReturnHasItsOwnLines() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : new String[] {"return.waiting-room", "return.countdown", "return.now"}) {
            assertTrue(english.containsKey(key), "no English line for " + key);
            assertTrue(german.containsKey(key), "no German line for " + key);
        }
        assertTrue(
                english.getProperty("return.countdown").contains("{seconds}"),
                "a countdown that does not name the number is a line that never changes");
        assertTrue(
                english.getProperty("return.waiting-room").contains("{what}"),
                "the line out of the waiting room names the server it is about");
    }

    private static Properties load(final String language) throws IOException {
        final Properties properties = new Properties();
        try (InputStream stream = HomecomingTest.class
                .getClassLoader()
                .getResourceAsStream("messages/proxy/" + language + ".properties")) {
            assertTrue(stream != null, "no bundle for " + language);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
