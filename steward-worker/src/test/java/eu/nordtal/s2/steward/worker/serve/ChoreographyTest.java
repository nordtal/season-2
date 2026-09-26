package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stage directions of a run: which standbys it needs, what a standby that will not come up
 * costs, and what happens when the players are still there (season-2-ops/122).
 *
 * <p>Every clock here is driven rather than slept through - a ten-second cap and a three-minute
 * patience are both things a test must reach in microseconds or not assert at all.</p>
 */
class ChoreographyTest {

    // -------------------------------------------------------------------------------------------
    // The trigger rule
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the limbo brings a limbo, the proxy brings a proxy, and the SMP brings neither")
    void theTriggerRule() {
        assertEquals(List.of("limbo-standby"), Choreography.standbysFor(Set.of("limbo")));
        assertEquals(List.of("proxy-standby"), Choreography.standbysFor(Set.of("proxy")));
        assertEquals(
                List.of("proxy-standby", "limbo-standby"),
                Choreography.standbysFor(Set.of("limbo", "proxy")),
                "the order is SERVICES_WITH_STANDBY's, so a swap always starts the proxy first");

        // The third of Till's three sentences, and the one with no line of its own: a run that
        // moves the SMP needs a waiting room the whole time, and the waiting room of a run that
        // leaves the limbo alone IS the limbo. Asking for a standby here would start a second
        // limbo for no reason on every ordinary backend update.
        assertEquals(
                List.of(),
                Choreography.standbysFor(Set.of("smp")),
                "an SMP-only run has a waiting room already - the live limbo");
        assertEquals(
                List.of("limbo-standby"),
                Choreography.standbysFor(Set.of("smp", "limbo")),
                "and when the run takes the waiting room too, the standby is the waiting room");

        assertEquals(List.of(), Choreography.standbysFor(Set.of("hunger-games", "discord-bot")));
    }

    // -------------------------------------------------------------------------------------------
    // Opening the window
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the standbys are started before anything else happens, and from the local image")
    void theStandbysComeUpFirst() {
        final FakeContainers containers = new FakeContainers().running("proxy", "limbo", "smp");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("proxy", "limbo", "smp"));

        assertTrue(window.opened(), window.refusal());
        assertEquals(List.of("proxy-standby", "limbo-standby"), window.standbys());
        // recreate-local, not recreate: the standby has to run the image its live service runs,
        // and on this deployment that image is very often built on the host.
        assertEquals(
                List.of("recreate-local:proxy-standby", "recreate-local:limbo-standby"),
                containers.calls,
                "a standby is made from the image already here, never fetched");
    }

    @Test
    @DisplayName("a standby that never becomes healthy aborts the run with nothing stopped")
    void anUnhealthyStandbyAbortsEverything() {
        final FakeContainers containers =
                new FakeContainers().running("proxy", "limbo").neverHealthy("limbo-standby");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("limbo"));

        assertFalse(window.opened(), "a run with nowhere to put the players must not go ahead");
        assertNotNull(window.refusal());
        assertTrue(window.refusal().contains("limbo-standby"), window.refusal());
        assertTrue(window.refusal().contains("unhealthy"), window.refusal());
        // And it leaves nothing behind: the container it started is stopped again, so a refused run
        // does not park a second network on this host until somebody notices.
        assertTrue(
                containers.calls.contains("stop:limbo-standby-container-2"),
                "a refused window stops what it started - " + containers.calls);
        assertTrue(
                containers.calls.stream().noneMatch(call -> call.startsWith("stop:limbo-c")),
                "nothing the run was going to update may be stopped by the abort: " + containers.calls);
    }

    @Test
    @DisplayName("a run that needs no standby opens an empty window and calls nothing")
    void noStandbyNeeded() {
        final FakeContainers containers = new FakeContainers().running("smp");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("smp"));

        assertTrue(window.opened());
        assertTrue(window.isEmpty());
        assertEquals(List.of(), containers.calls, "an SMP-only run touches no standby at all");
    }

    // -------------------------------------------------------------------------------------------
    // Waiting for the players
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an empty server is not waited for at all")
    void nobodyOnItMeansNoWait() {
        final Counts counts = new Counts().on("smp", 0);
        final Choreography choreography = new Choreography(new FakeContainers(), counts, driven());

        assertNull(choreography.waitUntilEmpty(List.of("smp")));
    }

    @Test
    @DisplayName("the wait ends the moment the last player is gone")
    void itEndsWhenTheyAreGone() {
        final Counts counts = new Counts().on("smp", 3);
        final Driven clock = driven();
        clock.onSleep(() -> counts.on("smp", 0));
        final Choreography choreography = new Choreography(new FakeContainers(), counts, clock);

        assertNull(choreography.waitUntilEmpty(List.of("smp")));
        assertEquals(
                Duration.ofSeconds(1),
                clock.slept(),
                "one poll, not the whole cap: the wait is for the players, not for the clock");
    }

    @Test
    @DisplayName("after the cap it stops anyway, and the report says with how many")
    void theCapStopsAnyway() {
        final Counts counts = new Counts().on("smp", 1);
        final Driven clock = driven();
        final Choreography choreography = new Choreography(new FakeContainers(), counts, clock);

        final String said = choreography.waitUntilEmpty(List.of("smp"));

        assertNotNull(said, "a stop with somebody still on it is a line in the report, not silence");
        assertEquals("stopped with 1 player still connected (smp: 1) after waiting 10s", said);
        assertTrue(clock.slept().compareTo(Choreography.EMPTY_CAP) >= 0, "it waited the whole cap before giving up");
    }

    @Test
    @DisplayName("nobody having said is not nobody being there")
    void silenceIsNotEmptiness() {
        // The failure this Optional exists for. online_count is written by the proxy; a proxy that
        // has stopped writing leaves a row that is minutes old, and reading that as "zero players"
        // would end the wait early on exactly the run where waiting mattered.
        final Choreography choreography = new Choreography(new FakeContainers(), Occupancy.NONE, driven());

        final String said = choreography.waitUntilEmpty(List.of("smp"));

        assertNotNull(said);
        assertTrue(said.contains("Nothing recent said how many players were on smp"), said);
    }

    @Test
    @DisplayName("the two halves of the sentence are told apart")
    void bothHalvesAreSaid() {
        final Map<String, Integer> occupied = new LinkedHashMap<>();
        occupied.put("smp", 2);
        final String said = Choreography.stoppedAnyway(occupied, List.of("limbo"));

        assertTrue(said.startsWith("stopped with 2 players still connected (smp: 2)"), said);
        assertTrue(said.contains("Nothing recent said how many players were on limbo"), said);
    }

    // -------------------------------------------------------------------------------------------
    // Closing the window
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the standby is stopped again once it is empty, and only once")
    void theStandbyIsStoppedAgain() {
        final FakeContainers containers = new FakeContainers().running("limbo");
        final Counts counts = new Counts().on("limbo-standby", 0);
        final Choreography choreography = new Choreography(containers, counts, driven());
        choreography.open(List.of("limbo"));
        containers.calls.clear();

        final List<String> said = choreography.close();

        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("limbo-standby"), said.toString());
        assertTrue(said.get(0).contains("stopped again"), said.toString());
        assertEquals(List.of("stop:limbo-standby-container-2"), containers.calls);

        // Idempotent, because the run closes its window on the ordinary path AND in a finally.
        assertEquals(List.of(), choreography.close());
        assertEquals(List.of("stop:limbo-standby-container-2"), containers.calls);
    }

    @Test
    @DisplayName("a standby still holding players is waited for before it is stopped")
    void itWaitsForTheStandbyToEmpty() {
        final FakeContainers containers = new FakeContainers().running("proxy");
        final Counts counts = new Counts().standbyProxy(2);
        final Driven clock = driven();
        clock.onSleep(() -> counts.standbyProxy(0));
        final Choreography choreography = new Choreography(containers, counts, clock);
        choreography.open(List.of("proxy"));
        containers.calls.clear();

        final List<String> said = choreography.close();

        assertEquals(List.of("stop:proxy-standby-container-2"), containers.calls);
        assertTrue(said.get(0).endsWith("has been stopped again"), said.toString());
        assertEquals(
                Duration.ofSeconds(1),
                clock.slept(),
                "it waited for the two players to go home before pulling the standby out" + " from under them");
    }

    // -------------------------------------------------------------------------------------------
    // The doubles
    // -------------------------------------------------------------------------------------------

    private static Driven driven() {
        return new Driven();
    }

    /** A clock that never sleeps; the sleep advances it and optionally changes the world. */
    private static final class Driven implements UpdateRun.Waiting {

        private Instant now = Instant.parse("2026-09-20T12:00:00Z");
        private Duration slept = Duration.ZERO;
        private Runnable onSleep = () -> {};

        void onSleep(final Runnable what) {
            this.onSleep = what;
        }

        Duration slept() {
            return slept;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public boolean sleep(final Duration duration) {
            now = now.plus(duration);
            slept = slept.plus(duration);
            onSleep.run();
            return true;
        }
    }

    /** An {@link Occupancy} that answers from a map, with no notion of staleness. */
    private static final class Counts implements Occupancy {

        private final Map<String, Integer> players = new LinkedHashMap<>();
        private Integer standbyProxy;

        Counts on(final String service, final int howMany) {
            players.put(service, howMany);
            return this;
        }

        Counts standbyProxy(final int howMany) {
            standbyProxy = howMany;
            return this;
        }

        @Override
        public OptionalInt on(final String service, final Instant now) {
            final Integer howMany = players.get(service);
            return howMany == null ? OptionalInt.empty() : OptionalInt.of(howMany);
        }

        @Override
        public OptionalInt onStandbyProxy(final Instant now) {
            return standbyProxy == null ? OptionalInt.empty() : OptionalInt.of(standbyProxy);
        }
    }
}
