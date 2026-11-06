package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The bot's own role keeps the cards in the real table. The test is skipped when no Docker daemon is reachable. */
class RunCardsIntegrationTest {

    private static Jdbi owner;
    private static RunCards cards;

    @BeforeAll
    static void startDatabase() {
        final TestDatabase postgres = TestDatabase.fresh();
        owner = Jdbi.create(postgres.dataSource());
        cards = RunCards.stored(Jdbi.create(postgres.dataSourceAs(DatabaseRole.DISCORD_BOT)));
    }

    private static long run() {
        return owner.withHandle(
                handle -> handle.createQuery("INSERT INTO steward_inbox (kind, actor_kind, status, finished)"
                                + " VALUES ('UPDATE', 'HOST', 'DONE', now()) RETURNING id")
                        .mapTo(Long.class)
                        .one());
    }

    @Test
    void aKeptCardIsReadBackAndItsAlertIsRecordedOnce() {
        final long run = run();
        assertEquals(Optional.empty(), cards.of(run));

        cards.keep(run, "1400000000000000001");
        assertEquals(Optional.of(new RunCards.Card("1400000000000000001", false)), cards.of(run));

        assertTrue(cards.alert(run), "the first alert");
        assertFalse(cards.alert(run), "a redelivered alert");
        assertEquals(Optional.of(new RunCards.Card("1400000000000000001", true)), cards.of(run));
    }

    @Test
    void anAlertBeforeAnyCardIsRecordedAndTheCardThatComesLaterKeepsIt() {
        final long run = run();

        assertTrue(cards.alert(run));
        cards.keep(run, "1400000000000000002");
        assertEquals(Optional.of(new RunCards.Card("1400000000000000002", true)), cards.of(run));
    }
}
