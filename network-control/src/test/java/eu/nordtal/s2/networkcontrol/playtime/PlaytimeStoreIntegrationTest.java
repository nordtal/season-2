package eu.nordtal.s2.networkcontrol.playtime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PlaytimeStore}'s one statement, against a real PostgreSQL running the real {@code V4}.
 * <p>
 * {@link PlaytimeWriterTest} covers the counting; this covers the writing, and the two are separate
 * because only one of them needs Docker. What cannot be checked in memory is that the row is
 * created on first use, that a second call <b>adds</b> rather than replaces, that the foreign key
 * onto {@code discord_user} holds, and that two flushes racing produce the sum of both slices
 * instead of one of them - which is the property {@code V4}'s comment claims for {@code bigint}
 * seconds over an {@code interval}.
 * </p>
 * <p>
 * Testcontainers is driven by hand from {@link BeforeAll} for the reason {@code :common}'s tests
 * give: the {@code org.testcontainers:junit-jupiter} extension is built against JUnit 5 and this
 * repo is on the JUnit 6 BOM. These tests <b>skip themselves</b> when no Docker daemon is
 * reachable, so a green build on a machine without Docker proves nothing about any of this.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaytimeStoreIntegrationTest {

    private static final String DISCORD_ID = "100000000000000001";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private PlaytimeStore store;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed play-time tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // The real migrations, off the classpath - :common is shaded into this module, so
        // db/migration is exactly where the bot finds it too.
        Flyway.configure(PlaytimeStoreIntegrationTest.class.getClassLoader())
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
    void freshStore() {
        execute("TRUNCATE TABLE player_playtime, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + DISCORD_ID + "')");
        store = PlaytimeStore.using(dataSource);
    }

    @Test
    void theFirstFlushOfASeasonCreatesTheRow() {
        store.add(DISCORD_ID, 90);

        assertEquals(90, seconds(DISCORD_ID));
        assertEquals(1, count("SELECT count(*) FROM player_playtime"));
    }

    @Test
    void everyFlushAddsRatherThanReplaces() {
        store.add(DISCORD_ID, 60);
        store.add(DISCORD_ID, 60);
        store.add(DISCORD_ID, 15);

        assertEquals(135, seconds(DISCORD_ID),
                "the proxy sends slices, never totals - a writer that replaced would lose a session "
                        + "every time two of them overlapped");
    }

    @Test
    void theUpdatedStampMovesWithEveryFlush() {
        store.add(DISCORD_ID, 10);
        final String first = single("SELECT updated FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'");

        store.add(DISCORD_ID, 10);
        final String second = single("SELECT updated FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'");

        assertTrue(second.compareTo(first) >= 0, first + " -> " + second);
    }

    @Test
    void concurrentFlushesAddUpInsteadOfOverwritingEachOther() throws Exception {
        // Two proxies, or one proxy's periodic sweep racing a disconnect. The statement is an
        // addition evaluated by PostgreSQL, so the row ends up with both slices in either order.
        final int writers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(writers);
        final CountDownLatch go = new CountDownLatch(1);
        try {
            for (int index = 0; index < writers; index++) {
                pool.submit(() -> {
                    go.await();
                    store.add(DISCORD_ID, 30);
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(writers * 30L, seconds(DISCORD_ID));
    }

    @Test
    void aDiscordAccountTheDatabaseHasNeverSeenIsRefusedByTheForeignKey() {
        // The writer only ever passes an id the login query returned, and this is what makes that
        // an enforced rule rather than a convention: a play-time row for a user that does not exist
        // would be a total nobody could ever attribute.
        assertThrows(RuntimeException.class, () -> store.add("999999999999999999", 60));
    }

    @Test
    void deletingTheUserTakesTheirPlayTimeWithIt() {
        store.add(DISCORD_ID, 120);

        execute("DELETE FROM discord_user WHERE discord_id = '" + DISCORD_ID + "'");

        assertEquals(0, count("SELECT count(*) FROM player_playtime"),
                "ON DELETE CASCADE, so nothing is left keyed by an account that is gone");
    }

    // ---------------------------------------------------------------- helpers

    private static long seconds(final String discordId) {
        return count("SELECT seconds FROM player_playtime WHERE discord_id = '" + discordId + "'");
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    private static long count(final String sql) {
        final String value = single(sql);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String single(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
        }
    }
}
