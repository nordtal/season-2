package eu.nordtal.season.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.SignalProbe;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.TestPools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Every write of a round, a team or a member signals {@link Channel#HUNGER_GAMES}, so the proxy's numbers follow. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationSignalIntegrationTest {

    private static final DiscordId OWNER = DiscordId.of("200000000000000001");
    private static final DiscordId PARTNER = DiscordId.of("200000000000000002");
    private static final DiscordId OTHER = DiscordId.of("200000000000000003");

    private static TestDatabase postgres;
    private static Database database;

    @BeforeAll
    static void startDatabase() {
        postgres = TestDatabase.fresh();
        database = TestPools.open(postgres.jdbcUrl(), postgres.username(), postgres.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void aRoundATeamAnInviteAndItsAnswersAllSignal() {
        final Teams teams = new Teams(database.jdbi(), Game.HUNGER_GAMES);

        try (SignalProbe probe = SignalProbe.on(postgres.dataSource(), Channel.HUNGER_GAMES)) {
            assertEquals(
                    RegistrationResult.Status.REGISTERED,
                    teams.register(OWNER, "Foxes").status());
            assertTrue(probe.signalled(), "a new round with its first team and owner");

            final InviteResult declined = teams.invite(OWNER.value(), PARTNER.value());
            assertTrue(probe.signalled(), "an invite");
            teams.decline(declined.memberId(), PARTNER.value());
            assertTrue(probe.signalled(), "a declined invite");

            final InviteResult accepted = teams.invite(OWNER.value(), OTHER.value());
            assertTrue(probe.signalled(), "a second invite");
            assertEquals(
                    AnswerResult.Status.ANSWERED,
                    teams.accept(accepted.memberId(), OTHER.value()).status());
            assertTrue(probe.signalled(), "an accepted invite, which puts a player on the team");
        }
    }
}
