package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests the standbys a run needs, what a failed one costs, and the wait for players.
 *
 * Every clock is driven.
 */
class ChoreographyTest {

    @Test
    void theLimboBringsALimboTheProxyBringsAProxyAndTheSmpBringsNeither() {
        assertEquals(List.of("limbo-standby"), Choreography.standbysFor(DeclaredTopology.topology(), Set.of("limbo")));
        assertEquals(List.of("proxy-standby"), Choreography.standbysFor(DeclaredTopology.topology(), Set.of("proxy")));
        assertEquals(
                List.of("proxy-standby", "limbo-standby"),
                Choreography.standbysFor(DeclaredTopology.topology(), Set.of("limbo", "proxy")),
                "the order is compose.yml's, so a swap always starts the proxy first");

        // A run that moves the SMP needs a waiting room, and when it leaves limbo alone, limbo is the waiting room.
        assertEquals(
                List.of(),
                Choreography.standbysFor(DeclaredTopology.topology(), Set.of("smp")),
                "an SMP-only run has a waiting room already - the live limbo");
        assertEquals(
                List.of("limbo-standby"),
                Choreography.standbysFor(DeclaredTopology.topology(), Set.of("smp", "limbo")),
                "and when the run takes the waiting room too, the standby is the waiting room");

        assertEquals(
                List.of(),
                Choreography.standbysFor(DeclaredTopology.topology(), Set.of("hunger-games", "discord-bot")));
    }

    @Test
    void theStandbysAreStartedBeforeAnythingElseHappensAndFromTheLocalImage() {
        final FakeContainers containers = new FakeContainers().running("proxy", "limbo", "smp");
        final Choreography choreography = choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("proxy", "limbo", "smp"));

        assertTrue(window.opened(), collected.rows().toString());
        assertEquals(List.of("proxy-standby", "limbo-standby"), window.standbys());
        // The standby must run the image its live service runs, which is often built here.
        assertEquals(
                List.of("recreate-local:proxy-standby", "recreate-local:limbo-standby"),
                containers.calls,
                "a standby is made from the image already here, never fetched");
    }

    @Test
    void aStandbyWhoseImageThisHostDoesNotHaveYetIsFetchedFirstAsAOneShotMeetsIt() {
        // A one-shot's compose file names the next release's image, which no container on this host runs yet.
        final FakeContainers containers =
                new FakeContainers().running("proxy", "limbo").imageNotHere("limbo-standby");
        final Choreography choreography = choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("limbo"));

        assertTrue(window.opened(), collected.rows().toString());
        assertEquals(
                List.of("fetch:limbo-standby", "recreate-local:limbo-standby"),
                containers.calls,
                "only a missing image is fetched, and the standby is then made from it");
    }

    @Test
    void aStandbyThatNeverBecomesHealthyAbortsTheRunWithNothingStopped() {
        final FakeContainers containers =
                new FakeContainers().running("proxy", "limbo").neverHealthy("limbo-standby");
        final Choreography choreography = choreography(containers, Occupancy.NONE, driven());

        final Choreography.Window window = choreography.open(List.of("limbo"));

        assertFalse(window.opened(), "a run with nowhere to put the players must not go ahead");
        assertEquals(
                List.of("STANDBY_UP limbo-standby FAILED NOT_HEALTHY", "STANDBY_DOWN limbo-standby DONE"), steps());
        // A refused run leaves no second network behind.
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

    @Test
    void anEmptyServerIsNotWaitedForAtAll() {
        final Counts counts = new Counts().on("smp", 0);
        final Choreography choreography = new Choreography(new FakeContainers(), counts, driven());

        assertEquals(Choreography.Left.EMPTY, choreography.waitUntilEmpty(List.of("smp")));
    }

    @Test
    void theWaitEndsTheMomentTheLastPlayerIsGone() {
        final Counts counts = new Counts().on("smp", 3);
        final Driven clock = driven();
        clock.onSleep(() -> counts.on("smp", 0));
        final Choreography choreography = new Choreography(new FakeContainers(), counts, clock);

        assertEquals(Choreography.Left.EMPTY, choreography.waitUntilEmpty(List.of("smp")));
        assertEquals(
                Duration.ofSeconds(1),
                clock.slept(),
                "one poll, not the whole cap: the wait is for the players, not for the clock");
    }

    @Test
    void aCountFromBeforeTheWaitDoesNotEndIt() {
        // The proxy wrote 0 a moment before the countdown ended; its next count still has the player on, mid-move.
        final Driven clock = driven();
        final Counts counts = new Counts().written("smp", 0, clock.now().minusMillis(500));
        clock.onSleep(() ->
                counts.written("smp", clock.slept().compareTo(Duration.ofSeconds(25)) < 0 ? 1 : 0, clock.now()));
        final Choreography choreography = new Choreography(new FakeContainers(), counts, clock);

        final Choreography.Left left = choreography.waitUntilEmpty(List.of("smp"));

        assertEquals(Choreography.Left.EMPTY, left, "a move that waits for the resource pack is waited for");
        assertEquals(Duration.ofSeconds(25), clock.slept(), "the stale zero did not end the wait at once");
    }

    @Test
    void afterTheCapItStopsAnywayAndTheReportSaysWithHowMany() {
        final Counts counts = new Counts().on("smp", 1);
        final Driven clock = driven();
        final Choreography choreography = new Choreography(new FakeContainers(), counts, clock);

        final Choreography.Left left = choreography.waitUntilEmpty(List.of("smp"));

        assertEquals(RunStep.Reason.PLAYERS_STAYED, left.reason());
        assertEquals(RunStep.Outcome.WARNING, left.outcome(), "a stop with somebody still on it is not silence");
        assertTrue(clock.slept().compareTo(Choreography.EMPTY_CAP) >= 0, "it waited the whole cap before giving up");
    }

    @Test
    void nobodyHavingSaidIsNotNobodyBeingThere() {
        // A proxy that stopped writing is not read as zero players.
        final Choreography choreography = new Choreography(new FakeContainers(), Occupancy.NONE, driven());

        final Choreography.Left left = choreography.waitUntilEmpty(List.of("smp"));

        assertEquals(RunStep.Reason.PLAYERS_UNKNOWN, left.reason(), "unknown is not the same as somebody stayed");
    }

    @Test
    void theStandbyIsStoppedAgainOnceItIsEmptyAndOnlyOnce() {
        final FakeContainers containers = new FakeContainers().running("limbo");
        final Counts counts = new Counts().on("limbo-standby", 0);
        final Choreography choreography = choreography(containers, counts, driven());
        choreography.open(List.of("limbo"));
        containers.calls.clear();

        choreography.close();

        assertEquals(List.of("STANDBY_UP limbo-standby DONE", "STANDBY_DOWN limbo-standby DONE"), steps());
        assertEquals(List.of("stop:limbo-standby-container-2", "hand-over-mounts:limbo-standby"), containers.calls);

        // Idempotent, because the run closes its window on the ordinary path AND in a finally.
        choreography.close();
        assertEquals(List.of("STANDBY_UP limbo-standby DONE", "STANDBY_DOWN limbo-standby DONE"), steps());
        assertEquals(List.of("stop:limbo-standby-container-2", "hand-over-mounts:limbo-standby"), containers.calls);
    }

    @Test
    void aStoppedStandbyIsHandedItsMountsAgainSinceTheInstallMirroredIntoThemAsRoot() {
        final FakeContainers containers = new FakeContainers().running("proxy", "limbo");
        final Choreography choreography = new Choreography(containers, Occupancy.NONE, driven());
        choreography.open(List.of("proxy", "limbo"));
        containers.calls.clear();

        choreography.close();

        assertEquals(
                List.of(
                        "stop:proxy-standby-container-2",
                        "hand-over-mounts:proxy-standby",
                        "stop:limbo-standby-container-2",
                        "hand-over-mounts:limbo-standby"),
                containers.calls,
                "the jars the install copied in belong to root until the standby's own user is handed them");
    }

    @Test
    void aStandbyStillHoldingPlayersIsWaitedForBeforeItIsStopped() {
        final FakeContainers containers = new FakeContainers().running("proxy");
        final Counts counts = new Counts().standbyProxy(2);
        final Driven clock = driven();
        clock.onSleep(() -> counts.standbyProxy(0));
        final Choreography choreography = choreography(containers, counts, clock);
        choreography.open(List.of("proxy"));
        containers.calls.clear();

        choreography.close();

        assertEquals(List.of("stop:proxy-standby-container-2", "hand-over-mounts:proxy-standby"), containers.calls);
        assertEquals(List.of("STANDBY_UP proxy-standby DONE", "STANDBY_DOWN proxy-standby DONE"), steps());
        assertEquals(
                Duration.ofSeconds(1),
                clock.slept(),
                "it waited for the two players to go home before pulling the standby out" + " from under them");
    }

    private final CollectedSteps collected = new CollectedSteps();

    private Choreography choreography(final ContainerOps containers, final Occupancy occupancy, final Waiting clock) {
        return new Choreography(containers, occupancy, clock, new Steps(collected, 1));
    }

    /** The steps written so far: kind, subject, outcome and reason. */
    private List<String> steps() {
        return collected.rows().stream()
                .map(row -> String.join(
                        " ",
                        java.util.stream.Stream.of(row.step(), row.service(), row.outcome(), row.reason())
                                .filter(java.util.Objects::nonNull)
                                .map(String::valueOf)
                                .toList()))
                .toList();
    }

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

    /** An {@link Occupancy} that answers from a map; a row is fresh unless it was {@link #written} at a set time. */
    private static final class Counts implements Occupancy {

        private final Map<String, Integer> players = new LinkedHashMap<>();
        private final Map<String, Instant> written = new LinkedHashMap<>();
        private Integer standbyProxy;

        Counts on(final String service, final int howMany) {
            players.put(service, howMany);
            written.remove(service);
            return this;
        }

        Counts written(final String service, final int howMany, final Instant at) {
            players.put(service, howMany);
            written.put(service, at);
            return this;
        }

        Counts standbyProxy(final int howMany) {
            standbyProxy = howMany;
            return this;
        }

        @Override
        public OptionalInt on(final String service, final Instant now, final Instant since) {
            final Integer howMany = players.get(service);
            if (howMany == null || !Occupancy.counts(written.getOrDefault(service, now), now, since)) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(howMany);
        }

        @Override
        public OptionalInt onStandbyProxy(final Instant now) {
            return standbyProxy == null ? OptionalInt.empty() : OptionalInt.of(standbyProxy);
        }
    }
}
