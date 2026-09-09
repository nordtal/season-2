package eu.nordtal.s2.smp.db;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The arithmetic behind {@code /aura}, against a real PostgreSQL running the real migrations.
 *
 * <h2>Why this needs a container</h2>
 * Every part of it is the database's. The place is a {@code count(*) FILTER (WHERE ...)}, which is
 * PostgreSQL's own syntax and not something a fake evaluates; the population is a join through
 * {@code account_link}, so who is counted is decided by a row existing rather than by any Java; and
 * the two numbers come back through a constructor mapper, where a renamed column is a runtime
 * failure and not a compile one.
 *
 * <p>What the numbers are <em>for</em> is the reason it is worth the container: {@code /aura} prints
 * "number 4 of 37" and then a list under it, and the list is drawn by
 * {@link SmpDao#topAura(int)} from exactly this population. If the two ever counted different people
 * the sentence would sit above a list that contradicts it - and nobody reports that, because it
 * looks like a leaderboard.</p>
 *
 * <p>It <b>skips itself</b> when no Docker daemon is reachable.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuraPlaceIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SmpDao dao;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed aura place tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure(AuraPlaceIntegrationTest.class.getClassLoader())
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
    void emptyBoard() {
        execute("TRUNCATE TABLE smp_player, account_link, discord_user CASCADE");
        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SmpDao.class);
    }

    @Test
    @DisplayName("an empty board puts the asker first of one, not first of nobody")
    void anEmptyBoard() {
        // The season's first day, before anybody has earned anything. This used to answer "1 of 0",
        // which is not odd-but-honest - it is wrong: the place counted the asker and the total did
        // not, because the total is a count of smp_player rows and the asker has none yet. The
        // command still prints "nobody has any aura yet" underneath, and the list is still empty.
        final AuraPlace place = dao.auraPlace(0, "100000000000000001");
        assertEquals(1, place.place());
        assertEquals(1, place.total());
    }

    @Test
    @DisplayName("a linked player with no aura row is counted in the total, once")
    void theAskerWithNoRow() {
        player("100000000000000001", 400);
        player("100000000000000002", 200);
        // Linked, because that is what the caller resolves the id through - and with no
        // smp_player row, because nothing has ever given them a point.
        linkedWithoutAura("100000000000000009");

        // The defect this argument exists for: five people on the board and a sixth asking makes
        // "6 of 6", never "6 of 5". The asker is at the bottom because zero is below everybody.
        final AuraPlace newcomer = dao.auraPlace(0, "100000000000000009");
        assertEquals(3, newcomer.place());
        assertEquals(3, newcomer.total());

        // And somebody who does have a row is not counted twice by the same expression.
        final AuraPlace established = dao.auraPlace(400, "100000000000000001");
        assertEquals(1, established.place());
        assertEquals(2, established.total());
    }

    @Test
    @DisplayName("the place counts everybody with strictly more, so ties share it")
    void tiesShareAPlace() {
        player("100000000000000001", 400);
        player("100000000000000002", 200);
        player("100000000000000003", 200);
        player("100000000000000004", 10);

        assertEquals(1, dao.auraPlace(400, "100000000000000001").place());
        // Both people on 200 are told the same thing, which is what a leaderboard means by a place.
        assertEquals(2, dao.auraPlace(200, "100000000000000002").place());
        assertEquals(2, dao.auraPlace(200, "100000000000000003").place());
        assertEquals(4, dao.auraPlace(10, "100000000000000004").place());
        assertEquals(4, dao.auraPlace(10, "100000000000000004").total(),
                "the total is everybody on the board");
    }

    @Test
    @DisplayName("somebody with no account link is counted by neither the place nor the list")
    void theSamePopulationAsTheBoard() {
        // The join is what makes the two agree. Without it "number 2 of 3" would sit above a list
        // of two, on a server where somebody bought access and never joined.
        player("100000000000000001", 400);
        player("100000000000000002", 200);
        execute("INSERT INTO discord_user (discord_id) VALUES ('100000000000000009')");
        execute("INSERT INTO smp_player (discord_id, aura) VALUES ('100000000000000009', 999)");

        assertEquals(2, dao.auraPlace(400, "100000000000000001").total());
        assertEquals(1, dao.auraPlace(400, "100000000000000001").place(),
                "the unlinked account has more aura than anybody and must not push a real player"
                        + " down a place they cannot see");
        assertEquals(2, dao.topAura(10).size(), "the list this sentence sits above");
    }

    private void linkedWithoutAura(final String discordId) {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + discordId + "')");
        execute("INSERT INTO account_link (discord_id, mc_uuid) VALUES ('" + discordId + "', '"
                + UUID.nameUUIDFromBytes(discordId.getBytes()) + "')");
    }

    private void player(final String discordId, final int aura) {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + discordId + "')");
        execute("INSERT INTO account_link (discord_id, mc_uuid) VALUES ('" + discordId + "', '"
                + UUID.nameUUIDFromBytes(discordId.getBytes()) + "')");
        execute("INSERT INTO smp_player (discord_id, aura) VALUES ('" + discordId + "', " + aura + ")");
    }

    private void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
