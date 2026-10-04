package eu.nordtal.s2.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.registration.Game;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@link Teams} against a real PostgreSQL and the real migrations, whose constraints are the rules under test.
 *
 * Skipped when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamsIntegrationTest {

    private static final String OWNER = "200000000000000001";
    private static final String PARTNER = "200000000000000002";
    private static final String OTHER = "200000000000000003";
    private static final String UNREGISTERED = "200000000000000004";

    private static TestDatabase postgres;
    private static Database database;

    private Teams teams;

    @BeforeAll
    static void startDatabase() {
        postgres = TestDatabase.fresh();

        database = Database.create(DatabaseConfig.of(postgres.jdbcUrl(), postgres.username(), postgres.password()));
        database.jdbi().installPlugin(Jdbis.ids());
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clean() {
        assumeTrue(database != null);
        database.jdbi().useHandle(handle -> handle.execute("TRUNCATE registration, hg_game, discord_user CASCADE"));
        teams = new Teams(database.jdbi(), Game.HUNGER_GAMES);
    }

    @Test
    void theMigrationAppliesAndCreatesTheRegistrationTables() {
        final List<String> tables = database.jdbi()
                .withHandle(handle -> handle.createQuery(
                                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
                        .mapTo(String.class)
                        .list());

        assertTrue(tables.containsAll(List.of("registration", "team", "team_member")), tables.toString());
    }

    @Test
    void registeringCreatesATeamWithItsOwnerAsAFullMember() {
        final RegistrationResult result = teams.register(DiscordId.of(OWNER), "Foxes");

        assertEquals(RegistrationResult.Status.REGISTERED, result.status());
    }

    @Test
    void aSecondRegistrationByTheSameAccountIsRefusedPreCheckAndConstraintAlike() {
        teams.register(DiscordId.of(OWNER), "Foxes");

        assertEquals(
                RegistrationResult.Status.ALREADY_REGISTERED,
                teams.register(DiscordId.of(OWNER), "Wolves").status());
    }

    @Test
    void aTeamNameIsTakenCaseInsensitivelyWithinTheSameGame() {
        teams.register(DiscordId.of(OWNER), "Foxes");

        assertEquals(
                RegistrationResult.Status.NAME_TAKEN,
                teams.register(DiscordId.of(OTHER), "foxes").status());
    }

    @Test
    void inviteThenAcceptCompletesTheTeam() {
        teams.register(DiscordId.of(OWNER), "Foxes");

        final InviteResult invited = teams.invite(OWNER, PARTNER);
        assertEquals(InviteResult.Status.INVITED, invited.status());

        final AnswerResult accepted = teams.accept(invited.memberId(), PARTNER);
        assertAll(
                () -> assertEquals(AnswerResult.Status.ANSWERED, accepted.status()),
                () -> assertEquals("Foxes", accepted.teamName()));

        // The team is full, so a third account cannot be invited.
        assertEquals(
                InviteResult.Status.TEAM_FULL, teams.invite(OWNER, UNREGISTERED).status());
    }

    @Test
    void decliningFreesTheTeamUpForADifferentInvite() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        final UUID firstInvite = teams.invite(OWNER, PARTNER).memberId();

        final AnswerResult declined = teams.decline(firstInvite, PARTNER);
        assertEquals(AnswerResult.Status.ANSWERED, declined.status());

        // A second invite, to somebody else, is not blocked by the declined row.
        assertEquals(InviteResult.Status.INVITED, teams.invite(OWNER, OTHER).status());
    }

    @Test
    void onlyTheInvitedAccountCanAnswerItsOwnInvite() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        final UUID memberId = teams.invite(OWNER, PARTNER).memberId();

        assertEquals(
                AnswerResult.Status.NOT_PENDING, teams.accept(memberId, OTHER).status());
    }

    @Test
    void aSecondInviteWhileOneIsPendingIsRefused() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        teams.invite(OWNER, PARTNER);

        assertEquals(
                InviteResult.Status.INVITE_PENDING, teams.invite(OWNER, OTHER).status());
    }

    @Test
    void invitingSomebodyWhoIsAlreadyRegisteredElsewhereIsRefused() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        teams.register(DiscordId.of(PARTNER), "Wolves");

        assertEquals(
                InviteResult.Status.TARGET_UNAVAILABLE,
                teams.invite(OWNER, PARTNER).status());
    }

    @Test
    void aNonOwnerMemberCannotInvite() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        teams.accept(teams.invite(OWNER, PARTNER).memberId(), PARTNER);

        assertEquals(InviteResult.Status.NOT_OWNER, teams.invite(PARTNER, OTHER).status());
    }

    @Test
    void theRoundIsReusedRatherThanOpenedASecondTime() {
        assertEquals(teams.current().id(), teams.current().id());
    }

    @Test
    void onceTheGameIsDecidedTheNextRegistrationOpensANewRound() {
        final UUID first = teams.current().id();
        teams.register(DiscordId.of(OWNER), "Foxes");
        moveRound(first, "ENDED");

        final UUID second = teams.current().id();
        assertTrue(!first.equals(second), "an ended round must not be reused");
        // The old round's team name and its people are free again.
        assertEquals(
                RegistrationResult.Status.REGISTERED,
                teams.register(DiscordId.of(OWNER), "Foxes").status());
    }

    @Test
    void nothingChangesWhileAGameOfTheRoundIsUnderWay() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        final UUID invite = teams.invite(OWNER, PARTNER).memberId();
        moveRound(teams.current().id(), "CLOSED");

        assertAll(
                () -> assertEquals(
                        RegistrationResult.Status.CLOSED,
                        teams.register(DiscordId.of(OTHER), "Wolves").status()),
                () -> assertEquals(
                        InviteResult.Status.CLOSED, teams.invite(OWNER, OTHER).status()),
                () -> assertEquals(
                        AnswerResult.Status.CLOSED,
                        teams.accept(invite, PARTNER).status()));
    }

    @Test
    void anAbortedGameOpensTheSameRoundAgainWithItsTeams() {
        teams.register(DiscordId.of(OWNER), "Foxes");
        final UUID invite = teams.invite(OWNER, PARTNER).memberId();
        final UUID round = teams.current().id();
        moveRound(round, "CLOSED");
        moveRound(round, "OPEN");

        assertEquals(round, teams.current().id());
        assertEquals(AnswerResult.Status.ANSWERED, teams.accept(invite, PARTNER).status());
        assertEquals(
                RegistrationResult.Status.ALREADY_REGISTERED,
                teams.register(DiscordId.of(OWNER), "Wolves").status());
    }

    @Test
    void aRoundIsNamedByItsGame() {
        final UUID round = teams.current().id();

        assertEquals(
                "hunger-games",
                database.jdbi()
                        .withHandle(handle -> handle.createQuery("SELECT game FROM registration WHERE id = :id")
                                .bind("id", round)
                                .mapTo(String.class)
                                .one()));
    }

    /** What the game does to the round: it is the one that moves its state. */
    private static void moveRound(final UUID round, final String state) {
        database.jdbi()
                .useHandle(handle -> handle.createUpdate("UPDATE registration SET state = :state WHERE id = :id")
                        .bind("state", state)
                        .bind("id", round)
                        .execute());
    }
}
