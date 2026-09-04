package eu.nordtal.s2.hungergames.db;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link HungerGamesDao#killCounts} against a real PostgreSQL running the real migrations.
 *
 * <h2>Why this needs a container</h2>
 * The whole of what {@code killCounts} does is SQL and JDBI column mapping, and no in-memory test
 * can say anything about either - the same argument {@code PlaytimeStoreIntegrationTest} makes for
 * its upsert. Two specific things here compile, pass every unit test in this module, and would
 * throw on the busiest tick of the event: PostgreSQL's {@code count(*)} is {@code bigint} while the
 * method answers {@code Map<UUID, Integer>}, and {@code @KeyColumn} / {@code @ValueColumn} name
 * columns as strings that nothing checks.
 *
 * <h2>Why the method exists at all</h2>
 * The ceremony used to ask {@link HungerGamesDao#killCount} once per member, inside a loop over
 * every player, <b>on the main thread</b> - forty participants in front of forty players is 1 600
 * blocking queries at the moment the whole event ends, and every one of them returns the same
 * answer, because the tally does not depend on who is being told. Found 2026-09-04.
 *
 * <p>It is also the standing proof that the tally and the tiebreak agree: {@code killCount} decides
 * who wins a tie and {@code killCounts} is what players are shown, so two different answers would
 * be a scoreboard that contradicts the result announced above it.
 *
 * <p>This test <b>skips itself</b> when no Docker daemon is reachable, so a green build on a
 * machine without Docker proves nothing about any of it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KillCountsIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private HungerGamesDao dao;
    private UUID gameId;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed kill tally tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // The real migrations off the classpath - :common is shaded into this module, so
        // db/migration is exactly where the plugin finds them on a server too.
        Flyway.configure(KillCountsIntegrationTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshGame() {
        execute("TRUNCATE TABLE hg_event, hg_member, hg_team, hg_game, discord_user CASCADE");

        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(HungerGamesDao.class);

        gameId = uuid("INSERT INTO hg_game (state) VALUES ('RUNNING') RETURNING id");
        final UUID teamId = uuid("INSERT INTO hg_team (game_id, name) VALUES ('" + gameId
                + "', 'reds') RETURNING id");
        alice = member(teamId, "100000000000000001");
        bob = member(teamId, "100000000000000002");
        carol = member(teamId, "100000000000000003");
    }

    @Test
    @DisplayName("one grouped query answers what one query per member used to")
    void theTallyIsOneRoundTrip() {
        kill(alice, bob);
        kill(alice, carol);
        kill(bob, carol);

        final Map<UUID, Integer> tally = dao.killCounts(gameId);

        assertEquals(2, tally.get(alice), "count(*) is bigint; this is the mapping that has to hold");
        assertEquals(1, tally.get(bob));
        assertEquals(Map.of(alice, 2, bob, 1), tally,
                "carol killed nobody and must simply be absent - the ceremony prints only the"
                        + " members above zero, so a zero row would be an extra line saying nothing");
    }

    @Test
    @DisplayName("the tally and the tiebreak never disagree")
    void theTallyAgreesWithTheTiebreak() {
        kill(alice, carol);
        kill(alice, bob);
        kill(bob, carol);

        final Map<UUID, Integer> tally = dao.killCounts(gameId);
        for (final UUID member : java.util.List.of(alice, bob, carol)) {
            assertEquals(dao.killCount(gameId, member), tally.getOrDefault(member, 0),
                    "killCount decides who wins a tie and killCounts is what players are shown; two"
                            + " answers is a scoreboard contradicting the result printed above it");
        }
    }

    @Test
    @DisplayName("only this game's KILL events count")
    void nothingElseIsCounted() {
        kill(alice, bob);
        execute("INSERT INTO hg_event (game_id, type, actor_id) VALUES ('" + gameId
                + "', 'BORDER_SHRINK', NULL)");
        execute("INSERT INTO hg_event (game_id, type, actor_id, victim_id) VALUES ('" + gameId
                + "', 'DEATH', '" + alice + "', '" + bob + "')");

        final UUID otherGame = uuid("INSERT INTO hg_game (state) VALUES ('DECIDED') RETURNING id");
        final UUID otherTeam = uuid("INSERT INTO hg_team (game_id, name) VALUES ('" + otherGame
                + "', 'blues') RETURNING id");
        execute("INSERT INTO hg_member (team_id, game_id, discord_id) VALUES ('" + otherTeam + "', '"
                + otherGame + "', '100000000000000001')");
        execute("INSERT INTO hg_event (game_id, type, actor_id) SELECT '" + otherGame
                + "', 'KILL', id FROM hg_member WHERE game_id = '" + otherGame + "'");

        assertEquals(Map.of(alice, 1), dao.killCounts(gameId),
                "a season runs more than one game, and a DEATH is not a KILL");
    }

    @Test
    @DisplayName("a game nobody killed in answers an empty map, not null")
    void anEmptyGameIsAnEmptyMap() {
        final Map<UUID, Integer> tally = dao.killCounts(gameId);
        assertTrue(tally.isEmpty(), "the ceremony iterates this; null would be an exception in front"
                + " of everybody at the end of the event");
    }

    // --- helpers ---------------------------------------------------------------------------

    private UUID member(final UUID teamId, final String discordId) {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + discordId + "')");
        return uuid("INSERT INTO hg_member (team_id, game_id, discord_id) VALUES ('" + teamId
                + "', '" + gameId + "', '" + discordId + "') RETURNING id");
    }

    private void kill(final UUID actor, final UUID victim) {
        execute("INSERT INTO hg_event (game_id, type, actor_id, victim_id) VALUES ('" + gameId
                + "', 'KILL', '" + actor + "', '" + victim + "')");
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private static UUID uuid(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getObject(1, UUID.class);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
