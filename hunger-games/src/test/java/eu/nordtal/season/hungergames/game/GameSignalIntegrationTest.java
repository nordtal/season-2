package eu.nordtal.season.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.RoundSeed;
import eu.nordtal.season.database.SignalProbe;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.notify.Channel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Every write of a game or of its round signals {@link Channel#HUNGER_GAMES}, so the proxy's numbers follow. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameSignalIntegrationTest {

    private static TestDatabase database;

    @BeforeAll
    static void startDatabase() {
        database = TestDatabase.fresh();
    }

    @Test
    void aGamesLifeAndItsEventsAllSignal() {
        final RoundSeed seed = new RoundSeed(database.dataSource());
        final GameDao dao =
                Jdbis.over(database.dataSourceAs(DatabaseRole.HUNGER_GAMES)).onDemand(GameDao.class);
        final UUID round = seed.round("OPEN");
        final UUID team = seed.team(round, "Foxes");
        final UUID member = seed.member(team, DiscordId.of("100000000000000001").value(), "OWNER");

        try (SignalProbe probe = SignalProbe.on(database.dataSource(), Channel.HUNGER_GAMES)) {
            final UUID game = dao.startGame(round).orElseThrow();
            assertTrue(probe.signalled(), "a start, which closes the round and opens the game");
            dao.release(game);
            assertTrue(probe.signalled(), "the end of the countdown");
            dao.recordEvent(game, "DEATH", null, member, null);
            assertTrue(probe.signalled(), "a death");
            dao.decideGame(game, member);
            assertTrue(probe.signalled(), "a decided game, which ends its round");

            final UUID again = seed.round("OPEN");
            assertTrue(dao.startGame(again).isPresent());
            assertTrue(probe.signalled(), "a second start");
            dao.abortInterrupted();
            assertTrue(probe.signalled(), "an abort, which opens its round again");
        }
    }
}
