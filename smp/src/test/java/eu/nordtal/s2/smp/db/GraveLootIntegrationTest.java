package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

/**
 * Closing a grave, against a real PostgreSQL running the real migrations.
 *
 * <h2>The failure it exists for</h2>
 * {@code smp_grave.looted_by} is {@code varchar(32)}, the same shape as {@code owner_id} beside it,
 * because every person in this schema is a discord id. {@code Graves#onClosed} passed the looter's
 * <em>Minecraft UUID</em>, whose 36 characters do not fit, so {@code markGraveLooted} threw
 * {@code value too long for type character varying(32)} on every single loot - and it threw from
 * inside the async task that erases the grave and refunds the experience, so none of that happened
 * either. No grave was ever marked looted, every grave was restored on every start, and nobody ever
 * got their levels back (finding 132).
 *
 * <p><b>Nothing in the game showed it.</b> The window opens, the items come out, the window closes -
 * which is the whole of what a player can check. It was found by reading {@code smp_grave} after a
 * real loot on the local stack.
 *
 * <h2>Why a container</h2>
 * The defect <em>is</em> the column width. An in-memory stand-in would accept both strings and stay
 * green through the whole bug. This drives the real statement against the real schema, and the
 * second case pins the width as the reason rather than as an accident - so widening the column
 * later is a decision somebody has to take on purpose.
 *
 * <p>It <b>skips itself</b> when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraveLootIntegrationTest {

    private static final String OWNER = "100000000000000042";
    private static final String LOOTER = "100000000000000043";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SmpDao dao;
    private UUID graveId;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed grave tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure(GraveLootIntegrationTest.class.getClassLoader())
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
    void freshGrave() {
        execute("TRUNCATE TABLE smp_grave, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + OWNER + "'), ('" + LOOTER + "')");
        graveId = UUID.randomUUID();
        execute("INSERT INTO smp_grave (id, owner_id, world, x, y, z, contents, experience)" + " VALUES ('" + graveId
                + "', '" + OWNER + "', 'nordtal', 1, 2, 3, '\\x00', 7)");

        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SmpDao.class);
    }

    @Test
    @DisplayName("a discord id closes the grave, and a second close does nothing")
    void aDiscordIdMarksItLooted() {
        assertTrue(
                dao.markGraveLooted(graveId, LOOTER).isPresent(), "a grave closed by a linked looter has to be marked");
        assertEquals(LOOTER, scalar("SELECT looted_by FROM smp_grave WHERE id = '" + graveId + "'"));

        // The guard is WHERE looted IS NULL, and it is what makes the experience refund happen
        // exactly once however many times the window is reopened.
        assertTrue(
                dao.markGraveLooted(graveId, LOOTER).isEmpty(),
                "a grave already looted must not be marked a second time");
    }

    /**
     * season-2-ingame/21, Till 2026-09-15: a grave is open to anyone, not only whoever died into
     * it - decided rather than found, and this is the check that decision asked for. "Rot sehen"
     * for this one means what it says only if a check exists to remove; there is none in
     * {@code markGraveLooted}'s {@code WHERE} clause today, so this is green from the first run,
     * and that absence is itself the finding the ticket wanted written down rather than a red run
     * invented to have one.
     */
    @Test
    @DisplayName("a looter who never owned the grave empties it all the same")
    void aStrangerMayEmptyAnyonesGrave() {
        assertTrue(
                dao.markGraveLooted(graveId, LOOTER).isPresent(),
                "LOOTER owns none of this grave's contents - owner_id on the row is OWNER - and it"
                        + " still closed. If this ever fails, somebody added the ownership check"
                        + " the ticket explicitly declined, and that belongs in a rules text and a"
                        + " release note, not a quiet WHERE clause");
    }

    @Test
    @DisplayName("an unlinked looter still closes the grave")
    void nullIsALegitimateLooter() {
        assertTrue(
                dao.markGraveLooted(graveId, null).isPresent(),
                "a grave has to close even when the person who emptied it has no discord account -"
                        + " the column is nullable for exactly that");
    }

    @Test
    @DisplayName("what is left after a half loot is written back, and not to a finished grave")
    void aPartialLootIsPersisted() {
        // The plugin's own map is this process's memory; the enable-time restore reads the row. So
        // a half-emptied grave came back full after any restart while the items already taken sat
        // in the looter's inventory - the same stack twice (finding 133).
        assertEquals(
                1,
                dao.updateGraveContents(graveId, new byte[] {1, 2, 3}),
                "what is left in a grave has to reach the row somebody will restore it from");
        assertEquals("\\x010203", scalar("SELECT contents FROM smp_grave WHERE id = '" + graveId + "'"));

        assertEquals(1, dao.markGraveLooted(graveId, LOOTER).isPresent() ? 1 : 0);
        assertEquals(
                0,
                dao.updateGraveContents(graveId, new byte[] {9}),
                "a grave somebody else finished a moment ago must not be refilled by a late close");
    }

    @Test
    @DisplayName("a grave older than the limit is deleted, with everything in it")
    void anOldGraveDecays() {
        // season-2-ingame/20. The time is given rather than waited for, which is the whole reason
        // this is a database test: `created` is the clock, so backdating the row is backdating the
        // grave, and no scheduler has to run for the statement to be the thing under test.
        execute("UPDATE smp_grave SET created = now() - interval '25 hours' WHERE id = '" + graveId + "'");
        final UUID fresh = UUID.randomUUID();
        execute("INSERT INTO smp_grave (id, owner_id, world, x, y, z, contents, experience)" + " VALUES ('" + fresh
                + "', '" + OWNER + "', 'nordtal', 9, 9, 9, '\\x00', 0)");

        final List<ExpiredGrave> gone = dao.expireGravesOlderThan(24);

        assertEquals(1, gone.size(), "exactly the old grave, and not the one made a moment ago");
        assertEquals(graveId, gone.getFirst().id());
        assertEquals(
                "nordtal",
                gone.getFirst().world(),
                "the world has to come back, because the"
                        + " display standing in it still has to be taken down and a sound made where it was");
        assertEquals(
                "0",
                scalar("SELECT count(*) FROM smp_grave WHERE id = '" + graveId + "'"),
                "the row is deleted rather than marked looted: nobody took it, and looted_by is"
                        + " already nullable for an unlinked looter - marking it would give"
                        + " \"who took it\" a wrong answer instead of no answer");
        assertEquals("1", scalar("SELECT count(*) FROM smp_grave WHERE id = '" + fresh + "'"));
    }

    @Test
    @DisplayName("a grave somebody emptied is left alone, however old it is")
    void alreadyLootedStays() {
        // The record of who took what is not a grave standing in the world, and the sweep has no
        // business in it. Without the `looted IS NULL` guard this row would vanish the day after.
        dao.markGraveLooted(graveId, LOOTER);
        execute("UPDATE smp_grave SET created = now() - interval '400 hours' WHERE id = '" + graveId + "'");

        assertEquals(List.of(), dao.expireGravesOlderThan(24));
        assertEquals(LOOTER, scalar("SELECT looted_by FROM smp_grave WHERE id = '" + graveId + "'"));
    }

    @Test
    @DisplayName("two graves of the same player have their own clocks")
    void eachGraveExpiresOnItsOwn() {
        // Till, 2026-09-15, asked directly what happens when somebody dies again while their old
        // grave still stands: several graves in parallel, each with its own countdown, and no
        // tidying up of the older one.
        final UUID second = UUID.randomUUID();
        execute("INSERT INTO smp_grave (id, owner_id, world, x, y, z, contents, experience, created)"
                + " VALUES ('" + second + "', '" + OWNER + "', 'nordtal', 4, 5, 6, '\\x00', 0,"
                + " now() - interval '23 hours')");
        execute("UPDATE smp_grave SET created = now() - interval '25 hours' WHERE id = '" + graveId + "'");

        assertEquals(
                List.of(graveId),
                dao.expireGravesOlderThan(24).stream().map(ExpiredGrave::id).toList());
        assertEquals(
                "1",
                scalar("SELECT count(*) FROM smp_grave WHERE id = '" + second + "'"),
                "the younger grave of the same player is untouched - two deaths are two graves");
    }

    /**
     * season-2-ingame/19: the hologram over a grave counts down against {@code created} plus the
     * configured limit, so {@code openGraves} has to hand that column back and {@link GraveRowMapper}
     * has to read it as the {@code timestamptz} it is, not drop it or silently null it.
     */
    @Test
    @DisplayName("an open grave carries when it was made, for the hologram to count down against")
    void openGravesCarryWhenTheyWereMade() {
        final GraveRow row = dao.openGraves().stream()
                .filter(candidate -> candidate.id().equals(graveId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fresh grave is not among the open ones"));

        assertTrue(row.created() != null, "created came back null for a row whose insert never set it to null");
        assertTrue(
                Duration.between(row.created(), Instant.now()).abs().toSeconds() < 60,
                "the fresh grave's created timestamp is not close to now: " + row.created());
    }

    @Test
    @DisplayName("a Minecraft UUID does not fit, which is why a discord id is what goes in")
    void aMinecraftUuidIsRefusedByTheColumn() {
        final RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> dao.markGraveLooted(graveId, UUID.randomUUID().toString()),
                "a 36-character UUID must not fit varchar(32) - if it ever does, somebody widened"
                        + " the column and the schema no longer says that a person here is a"
                        + " discord id");
        assertTrue(
                causeChain(thrown).contains("character varying(32)"),
                "the refusal has to be the column width rather than any old failure, so this test"
                        + " cannot stay green for the wrong reason: " + causeChain(thrown));
    }

    private static String causeChain(final Throwable thrown) {
        final StringBuilder text = new StringBuilder();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            text.append(cause).append(" | ");
        }
        return text.toString();
    }

    private String scalar(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var results = statement.executeQuery(sql)) {
            return results.next() ? results.getString(1) : null;
        } catch (final SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
