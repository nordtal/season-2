package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Counts the players a run moved out back: a late one is waited for, one who left is warned about at the cap.
 *
 * The roster and move counts live in a real database; the containers are stand-ins and the clock is driven.
 */
class RunPlayersBackTest {

    private final FakeContainers containers =
            new FakeContainers().running("smp", "hunger-games", "discord-bot", "limbo");
    private final List<RunStage> progress = new ArrayList<>();

    /** Runs on every sleep while players back is open, with the number of that sleep, as the proxy's waiting room. */
    private IntConsumer waitingRoom = slept -> {};

    private int sleptInBack;

    /** Where the proxy's roster says each player is. */
    private final Map<UUID, String> where = new LinkedHashMap<>();

    private final Waiting clock = driven();

    private TestDatabase postgres;
    private Database database;
    private UpdateDirectory directory;
    private Runner runner;

    @BeforeEach
    void open() {
        open(TestDatabase::fresh);
    }

    void open(final Supplier<TestDatabase> databases) {
        postgres = databases.get();
        database = Database.open(
                new DatabaseSpec() {
                    @Override
                    public String jdbcUrl() {
                        return postgres.jdbcUrl();
                    }

                    @Override
                    public String username() {
                        return postgres.username();
                    }

                    @Override
                    public String password() {
                        return postgres.password();
                    }
                },
                "steward-test");
        directory = Schema.updates(database);
        runner = new Runner(defaults(), database, containers, new FakeSnapshots(containers.calls), directory, clock);
    }

    Database database() {
        return database;
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void aReturnThroughARestartedProxyCompletesTheCombinedStop() {
        containers.running("proxy");
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("proxy", "smp"));
        final UUID player = online("smp");
        containers.watcher = call -> {
            if (call.equals("stop:smp-container")) {
                directory.recorder().moved(request.id(), "proxy", "proxy-standby", 1);
                directory.recorder().moved(request.id(), "smp", "limbo-standby", 1);
                directory.recorder().rememberMove(request.id(), player, "smp", "limbo-standby", false);
                where.put(player, "limbo-standby");
            }
        };
        waitingRoom = slept -> {
            if (slept == 1) {
                directory.recorder().returned("proxy", "proxy-standby", 1);
                where.put(player, "limbo-standby");
            }
            if (slept == 2) {
                UpdateDirectory.using(database.dataSource()).recorder().takeReturn(player);
                where.put(player, "smp");
            }
        };
        final Outcome outcome = runner.run(request, progress::add);
        assertEquals(UpdateStatus.DONE, outcome.status());
        assertTrue(
                steps(request.id()).contains("PLAYERS_BACK DONE"),
                steps(request.id()).toString());
        assertTrue(directory.recorder().moves(request.id()).stream().allMatch(move -> move.returned() == move.moved()));
    }

    @Test
    void playersBackWaitsPastItsCapForAPlayerTheWaitingRoomStillHolds() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        final UUID player = online("smp");
        movedOnStop(request, "limbo", player);
        // The proxy lets the player on only once the server answers and the queue's turn comes, after the cap.
        waitingRoom = slept -> {
            if (slept == 45) {
                directory.recorder().returned("smp", "limbo", 1);
                where.put(player, "smp");
            }
        };

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status());
        assertEquals(
                "PLAYERS_BACK DONE",
                steps(request.id()).getLast(),
                steps(request.id()).toString());
    }

    @Test
    void aPlayerMovedIntoTheStandbyWhoWaitsInLimboOnceItStopsStillCountsAsWaiting() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        final UUID player = online("smp");
        containers.watcher = call -> {
            if (call.equals("stop:smp-container")) {
                directory.recorder().moved(request.id(), "smp", "limbo-standby", 1);
                where.put(player, "limbo");
            }
        };
        waitingRoom = slept -> {
            if (slept == 45) {
                directory.recorder().returned("smp", "limbo-standby", 1);
                where.put(player, "smp");
            }
        };

        runner.run(request, progress::add);

        assertEquals(
                "PLAYERS_BACK DONE",
                steps(request.id()).getLast(),
                steps(request.id()).toString());
    }

    @Test
    void aPlayerTheWaitingRoomNeverLetsGoEndsAsLostOnceThePatienceRunsOut() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        movedOnStop(request, "limbo", online("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status());
        assertEquals("PLAYERS_BACK WARNING PLAYERS_LOST", steps(request.id()).getLast());
        assertTrue(
                sleptInBack >= PlayersBack.STALL.toSeconds() - 1,
                "a player still waiting is given the whole patience: " + sleptInBack);
    }

    @Test
    void aLongQueueThatKeepsLettingPlayersOnIsWaitedForToTheLast() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        final List<UUID> players = new ArrayList<>();
        for (int one = 0; one < 16; one++) {
            players.add(online("smp"));
        }
        movedOnStop(request, "limbo", players.toArray(UUID[]::new));
        // One player onto the server every twenty seconds, the last after more than five minutes.
        waitingRoom = slept -> {
            if (slept % 20 == 0 && slept / 20 <= players.size()) {
                directory.recorder().returned("smp", "limbo", 1);
                where.put(players.get(slept / 20 - 1), "smp");
            }
        };

        runner.run(request, progress::add);

        assertEquals(
                "PLAYERS_BACK DONE",
                steps(request.id()).getLast(),
                steps(request.id()).toString());
        assertTrue(sleptInBack >= 320, "the last player came back after " + sleptInBack);
    }

    @Test
    void aPlayerWhoLeftIsLostAtTheCapWhateverElseWaitsInLimbo() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        final UUID quit = online("smp");
        final UUID joining = UUID.randomUUID();
        containers.watcher = call -> {
            if (call.equals("stop:smp-container")) {
                directory.recorder().moved(request.id(), "smp", "limbo", 1);
                where.remove(quit);
                // A login waits in limbo for its resource pack, which says nothing about the player who quit.
                where.put(joining, "limbo");
            }
        };

        runner.run(request, progress::add);

        assertEquals("PLAYERS_BACK WARNING PLAYERS_LOST", steps(request.id()).getLast());
        assertTrue(sleptInBack <= PlayersBack.CAP.toSeconds() + 1, "nobody it moved still waits: " + sleptInBack);
    }

    @Test
    void playersMaintenanceKeepsInTheWaitingRoomAreNotLost() {
        maintenance();
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        movedOnStop(request, "limbo", online("smp"), online("smp"));

        runner.run(request, progress::add);

        assertEquals(
                "PLAYERS_BACK DONE",
                steps(request.id()).getLast(),
                steps(request.id()).toString());
        assertTrue(
                sleptInBack <= PlayersBack.CAP.toSeconds() + 1,
                "maintenance holds them on purpose, so nothing is waited for: " + sleptInBack);
    }

    @Test
    void maintenanceHidesNoPlayerWhoLeftBehindTheOnesItHolds() {
        maintenance();
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));
        final UUID quit = online("smp");
        containers.watcher = call -> {
            if (call.equals("stop:smp-container")) {
                directory.recorder().moved(request.id(), "smp", "limbo", 1);
                where.remove(quit);
                // Members maintenance held in limbo all along, not moved by this run.
                for (int held = 0; held < 3; held++) {
                    where.put(UUID.randomUUID(), "limbo");
                }
            }
        };

        runner.run(request, progress::add);

        assertEquals("PLAYERS_BACK WARNING PLAYERS_LOST", steps(request.id()).getLast());
    }

    @Test
    void aPlayerAHeldServiceKeepsInLimboHidesNoLossOfAnother() {
        maintenance();
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp", "hunger-games"));
        final UUID quit = online("smp");
        final UUID kept = online("hunger-games");
        containers.watcher = call -> {
            if (call.equals("stop:smp-container")) {
                directory.recorder().moved(request.id(), "smp", "limbo", 1);
                directory.recorder().moved(request.id(), "hunger-games", "limbo", 1);
                // Held once the run has stopped it: its player waits in limbo on purpose, which says nothing of smp's.
                directory.hold("hunger-games", Actor.HOST, null);
                where.remove(quit);
                where.put(kept, "limbo");
            }
        };

        runner.run(request, progress::add);

        assertEquals(
                "PLAYERS_BACK WARNING PLAYERS_LOST",
                steps(request.id()).getLast(),
                steps(request.id()).toString());
        assertTrue(sleptInBack <= PlayersBack.CAP.toSeconds() + 1, "nobody smp lost still waits: " + sleptInBack);
    }

    /** Puts a new player on {@code service} in the proxy's roster, written on every sleep. */
    private UUID online(final String service) {
        final UUID player = UUID.randomUUID();
        where.put(player, service);
        writeRoster(clock.now());
        return player;
    }

    /** Has the proxy move the players into {@code room} as the run stops smp, and keep them there. */
    private void movedOnStop(final UpdateRequest request, final String room, final UUID... players) {
        containers.watcher = call -> {
            if (call.equals("stop:smp-container")) {
                directory.recorder().moved(request.id(), "smp", room, players.length);
                for (final UUID player : players) {
                    where.put(player, room);
                }
            }
        };
    }

    private void maintenance() {
        SeasonDirectory.using(database.dataSource(), Clock.systemUTC()).setMaintenance(true, Actor.HOST, null);
    }

    private void writeRoster(final Instant at) {
        OnlineRoster.using(database.dataSource(), () -> at)
                .replace(where.entrySet().stream()
                        .map(player -> new OnlineRoster.Presence(player.getKey(), "somebody", player.getValue()))
                        .toList());
    }

    private List<String> steps(final long run) {
        final List<String> steps = new ArrayList<>();
        try (Connection connection = database.dataSource().getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT concat_ws(' ', step, service, outcome, reason) FROM run_step WHERE run = ? ORDER BY seq")) {
            query.setLong(1, run);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    steps.add(rows.getString(1));
                }
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return steps;
    }

    private UpdateRequest claimed(final UpdateKind kind, final List<String> services) {
        directory.submit(kind, Actor.HOST, Duration.ZERO, services);
        return directory.claimNext().orElseThrow();
    }

    /** A clock that moves only when the run sleeps, writing the roster and letting started servers turn healthy. */
    private Waiting driven() {
        return new Waiting() {
            private Instant now = Instant.now();

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public boolean sleep(final Duration duration) {
                now = now.plus(duration);
                containers.settle();
                if (!where.isEmpty()) {
                    writeRoster(now);
                }
                if (steps(directory.latestId()).contains("PLAYERS_BACK")) {
                    waitingRoom.accept(++sleptInBack);
                }
                return true;
            }
        };
    }

    private static RunSpec defaults() {
        return new RunSpec() {

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }
        };
    }
}
