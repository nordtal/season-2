package eu.nordtal.s2.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.time.Waiting;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The stage directions of a run: the standbys it needs, what a failed one costs, and players still online.
 *
 * Every clock is driven rather than slept through.
 */
class ChoreographyTest {

    // The trigger rule

    @Test
    void theLimboBringsALimboTheProxyBringsAProxyAndTheSmpBringsNeither() {
        assertEquals(List.of("limbo-standby"), Choreography.standbysFor(Set.of("limbo")));
        assertEquals(List.of("proxy-standby"), Choreography.standbysFor(Set.of("proxy")));
        assertEquals(
                List.of("proxy-standby", "limbo-standby"),
                Choreography.standbysFor(Set.of("limbo", "proxy")),
                "the order is SERVICES_WITH_STANDBY's, so a swap always starts the proxy first");

        // A run that moves the SMP needs a waiting room the whole time, and if it leaves limbo alone, limbo IS it.
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

    // Opening the window

    @Test
    void theStandbysAreStartedBeforeAnythingElseHappensAndFromTheLocalImage() {
        final FakeContainers containers = new FakeContainers().running("proxy", "limbo", "smp");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("proxy", "limbo", "smp"));

        assertTrue(window.opened(), window.refusal());
        assertEquals(List.of("proxy-standby", "limbo-standby"), window.standbys());
        // recreate-local, not recreate: the standby must run the image its live service runs, often built here.
        assertEquals(
                List.of("recreate-local:proxy-standby", "recreate-local:limbo-standby"),
                containers.calls,
                "a standby is made from the image already here, never fetched");
    }

    @Test
    void aStandbyThatNeverBecomesHealthyAbortsTheRunWithNothingStopped() {
        final FakeContainers containers =
                new FakeContainers().running("proxy", "limbo").neverHealthy("limbo-standby");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("limbo"));

        assertFalse(window.opened(), "a run with nowhere to put the players must not go ahead");
        assertNotNull(window.refusal());
        assertTrue(window.refusal().contains("limbo-standby"), window.refusal());
        assertTrue(window.refusal().contains("unhealthy"), window.refusal());
        // And it leaves nothing behind: a refused run does not park a second network on this host unnoticed.
        assertTrue(
                containers.calls.contains("stop:limbo-standby-container-2"),
                "a refused window stops what it started - " + containers.calls);
        assertTrue(
                containers.calls.stream().noneMatch(call -> call.startsWith("stop:limbo-c")),
                "nothing the run was going to update may be stopped by the abort: " + containers.calls);
    }

    @Test
    void aRunThatNeedsNoStandbyOpensAnEmptyWindowAndCallsNothing() {
        final FakeContainers containers = new FakeContainers().running("smp");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("smp"));

        assertTrue(window.opened());
        assertTrue(window.isEmpty());
        assertEquals(List.of(), containers.calls, "an SMP-only run touches no standby at all");
    }

    // Waiting for the players

    @Test
    void anEmptyServerIsNotWaitedForAtAll() {
        final Counts counts = new Counts().on("smp", 0);
        final Choreography choreography = new Choreography(new FakeContainers(), counts, driven());

        assertNull(choreography.waitUntilEmpty(List.of("smp")));
    }

    @Test
    void theWaitEndsTheMomentTheLastPlayerIsGone() {
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
    void afterTheCapItStopsAnywayAndTheReportSaysWithHowMany() {
        final Counts counts = new Counts().on("smp", 1);
        final Driven clock = driven();
        final Choreography choreography = new Choreography(new FakeContainers(), counts, clock);

        final String said = choreography.waitUntilEmpty(List.of("smp"));

        assertNotNull(said, "a stop with somebody still on it is a line in the report, not silence");
        assertEquals("stopped with 1 player still connected (smp: 1) after waiting 10s", said);
        assertTrue(clock.slept().compareTo(Choreography.EMPTY_CAP) >= 0, "it waited the whole cap before giving up");
    }

    @Test
    void nobodyHavingSaidIsNotNobodyBeingThere() {
        // The failure this Optional exists for: a proxy that stopped writing must never be read as "zero players".
        final Choreography choreography = new Choreography(new FakeContainers(), Occupancy.NONE, driven());

        final String said = choreography.waitUntilEmpty(List.of("smp"));

        assertNotNull(said);
        assertTrue(said.contains("Nothing recent said how many players were on smp"), said);
    }

    @Test
    void theTwoHalvesOfTheSentenceAreToldApart() {
        final Map<String, Integer> occupied = new LinkedHashMap<>();
        occupied.put("smp", 2);
        final String said = Choreography.stoppedAnyway(occupied, List.of("limbo"));

        assertTrue(said.startsWith("stopped with 2 players still connected (smp: 2)"), said);
        assertTrue(said.contains("Nothing recent said how many players were on limbo"), said);
    }

    // Closing the window

    @Test
    void theStandbyIsStoppedAgainOnceItIsEmptyAndOnlyOnce() {
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
    void aStandbyStillHoldingPlayersIsWaitedForBeforeItIsStopped() {
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

    // The doubles

    private static Driven driven() {
        return new Driven();
    }

    /** A clock that never sleeps; the sleep advances it and optionally changes the world. */
    private static final class Driven implements Waiting {

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
