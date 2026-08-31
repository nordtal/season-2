package eu.nordtal.s2.discordbot.hungergames;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Teams} against a real PostgreSQL, running the real {@code V5__hunger_games.sql}
 * migration.
 * <p>
 * Every rule here - one active membership per player per game, one team name per game, at most one
 * non-DECIDED game, one partner maximum - is a schema constraint, the same reasoning
 * {@code PaymentRequestIntegrationTest} gives for why an in-memory stand-in would prove nothing.
 * Skips itself when no Docker daemon is reachable.
 * </p>
 * <p>
 * What this <b>cannot</b> prove: anything about Discord - buttons, modals, DMs and the managed
 * Register message need a real guild, and nothing here exercises {@code RegisterFlow} or
 * {@code RegisterMessages}.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamsIntegrationTest {

    private static final String OWNER = "200000000000000001";
    private static final String PARTNER = "200000000000000002";
    private static final String OTHER = "200000000000000003";
    private static final String UNREGISTERED = "200000000000000004";

    private static PostgreSQLContainer<?> postgres;
    private static Database database;

    private Teams teams;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("hungergames")
                .withUsername("hungergames")
                .withPassword("hungergames");
        postgres.start();

        database = Database.create(DatabaseConfig.of(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clean() {
        assumeTrue(database != null);
        database.jdbi().useHandle(handle -> handle.execute(
                "TRUNCATE hg_event, hg_member, hg_team, hg_game, discord_user CASCADE"));
        teams = new Teams(database.jdbi());
    }

    @Test
    @DisplayName("the migration applies and creates the hunger games tables")
    void migrationCreatesEverything() {
        final List<String> tables = database.jdbi().withHandle(handle -> handle
                .createQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
                .mapTo(String.class)
                .list());

        assertTrue(tables.containsAll(List.of("hg_game", "hg_team", "hg_member", "hg_event")), tables.toString());
    }

    @Test
    @DisplayName("registering creates a team with its owner as a full member")
    void registeringCreatesTheTeam() {
        final RegistrationResult result = teams.register(OWNER, "Foxes");

        assertEquals(RegistrationResult.Status.REGISTERED, result.status());
    }

    @Test
    @DisplayName("a second registration by the same account is refused, pre-check and constraint alike")
    void secondRegistrationByTheSameAccountIsRefused() {
        teams.register(OWNER, "Foxes");

        assertEquals(RegistrationResult.Status.ALREADY_REGISTERED, teams.register(OWNER, "Wolves").status());
    }

    @Test
    @DisplayName("a team name is taken case-insensitively, within the same game")
    void teamNameIsTakenCaseInsensitively() {
        teams.register(OWNER, "Foxes");

        assertEquals(RegistrationResult.Status.NAME_TAKEN, teams.register(OTHER, "foxes").status());
    }

    @Test
    @DisplayName("invite, then accept, completes the team")
    void inviteThenAcceptCompletesTheTeam() {
        teams.register(OWNER, "Foxes");

        final InviteResult invited = teams.invite(OWNER, PARTNER);
        assertEquals(InviteResult.Status.INVITED, invited.status());

        final AnswerResult accepted = teams.accept(invited.memberId(), PARTNER);
        assertAll(
                () -> assertEquals(AnswerResult.Status.ANSWERED, accepted.status()),
                () -> assertEquals("Foxes", accepted.teamName())
        );

        // The team is full now - a third, otherwise uninvolved account cannot be invited too.
        assertEquals(InviteResult.Status.TEAM_FULL, teams.invite(OWNER, UNREGISTERED).status());
    }

    @Test
    @DisplayName("declining frees the team up for a different invite")
    void decliningFreesTheTeamUpForADifferentInvite() {
        teams.register(OWNER, "Foxes");
        final UUID firstInvite = teams.invite(OWNER, PARTNER).memberId();

        final AnswerResult declined = teams.decline(firstInvite, PARTNER);
        assertEquals(AnswerResult.Status.ANSWERED, declined.status());

        // A second invite, to somebody else, is not blocked by the declined row.
        assertEquals(InviteResult.Status.INVITED, teams.invite(OWNER, OTHER).status());
    }

    @Test
    @DisplayName("only the invited account can answer its own invite")
    void onlyTheInvitedAccountCanAnswerItsOwnInvite() {
        teams.register(OWNER, "Foxes");
        final UUID memberId = teams.invite(OWNER, PARTNER).memberId();

        assertEquals(AnswerResult.Status.NOT_PENDING, teams.accept(memberId, OTHER).status());
    }

    @Test
    @DisplayName("a second invite while one is pending is refused")
    void aSecondInviteWhileOneIsPendingIsRefused() {
        teams.register(OWNER, "Foxes");
        teams.invite(OWNER, PARTNER);

        assertEquals(InviteResult.Status.INVITE_PENDING, teams.invite(OWNER, OTHER).status());
    }

    @Test
    @DisplayName("inviting somebody who is already registered elsewhere is refused")
    void invitingSomebodyAlreadyRegisteredElsewhereIsRefused() {
        teams.register(OWNER, "Foxes");
        teams.register(PARTNER, "Wolves");

        assertEquals(InviteResult.Status.TARGET_UNAVAILABLE, teams.invite(OWNER, PARTNER).status());
    }

    @Test
    @DisplayName("a non-owner member cannot invite")
    void aNonOwnerMemberCannotInvite() {
        teams.register(OWNER, "Foxes");
        teams.accept(teams.invite(OWNER, PARTNER).memberId(), PARTNER);

        assertEquals(InviteResult.Status.NOT_OWNER, teams.invite(PARTNER, OTHER).status());
    }

    @Test
    @DisplayName("openGame reuses the one non-DECIDED game rather than creating a second")
    void openGameReusesTheOneNonDecidedGame() {
        final UUID first = teams.openGame();
        final UUID second = teams.openGame();

        assertEquals(first, second);
    }

    @Test
    @DisplayName("once the open game is DECIDED, the next registration opens a new one")
    void aNewGameOpensOnceThePreviousOneIsDecided() {
        final UUID first = teams.openGame();
        database.jdbi().useHandle(handle -> handle
                .createUpdate("UPDATE hg_game SET state = 'DECIDED' WHERE id = :id")
                .bind("id", first)
                .execute());

        final UUID second = teams.openGame();
        assertTrue(!first.equals(second), "a DECIDED game must not be reused");

        // The old game's team name is free again in the new game - a rehearsal and the real event
        // do not fight over "Foxes".
        database.jdbi().useHandle(handle -> handle
                .createUpdate("INSERT INTO hg_team (game_id, name) VALUES (:gameId, 'Foxes')")
                .bind("gameId", first)
                .execute());
        assertEquals(RegistrationResult.Status.REGISTERED, teams.register(OWNER, "Foxes").status());
    }
}
