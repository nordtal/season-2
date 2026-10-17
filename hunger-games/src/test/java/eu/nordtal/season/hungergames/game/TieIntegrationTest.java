package eu.nordtal.season.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.RoundSeed;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Runs {@link GameDao#recordTie} against a real PostgreSQL: a tie's winner is a reference to the member. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TieIntegrationTest {
    private static DataSource dataSource;

    private GameDao dao;
    private Jdbi jdbi;
    private UUID gameId;
    private UUID alice;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshGame() {
        final RoundSeed seed = new RoundSeed(dataSource);
        seed.execute("TRUNCATE TABLE hg_event, hg_game, registration, discord_user CASCADE");
        jdbi = Jdbis.over(dataSource);
        dao = jdbi.onDemand(GameDao.class);
        final UUID round = seed.round("CLOSED");
        gameId = seed.game(round, "RUNNING");
        alice = seed.member(seed.team(round, "reds"), "100000000000000001", "OWNER");
    }

    @Test
    void aBrokenTieReadsBackWithItsWinnerAsTheMember() {
        dao.recordTie(gameId, alice);

        assertEquals(List.of(Optional.of(alice)), tieWinners());
    }

    @Test
    void aTieWithoutAWinnerHasNoReference() {
        dao.recordTie(gameId, null);

        assertEquals(List.of(Optional.empty()), tieWinners());
    }

    @Test
    void anEventHasNoDetailText() {
        assertEquals(
                0L,
                (long) jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'hg_event' AND column_name = 'detail'")
                        .mapTo(Long.class)
                        .one()));
    }

    private List<Optional<UUID>> tieWinners() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT actor_id FROM hg_event WHERE type = 'TIE'")
                .map((rs, ctx) -> Optional.ofNullable(rs.getObject("actor_id", UUID.class)))
                .list());
    }
}
