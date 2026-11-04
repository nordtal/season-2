package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** Holds that a duel record keeps a kit for every row once the duel type is gone, and that it cannot lack one. */
class DuelKitOnlyUpgradeIntegrationTest {

    @Test
    void aRowWithoutAKitGetsItFromItsTypeAndTheTypeGoes() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "107");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO discord_user (discord_id) VALUES ('100000000000000042')");
            handle.execute("""
                    INSERT INTO smp_aura_event (id, discord_id, delta, reason) VALUES
                        ('00000000-0000-0000-0000-000000000001', '100000000000000042', 10, 'DUEL_WIN'),
                        ('00000000-0000-0000-0000-000000000002', '100000000000000042', -10, 'DUEL_LOSS'),
                        ('00000000-0000-0000-0000-000000000003', '100000000000000042', 10, 'DUEL_WIN')
                    """);
            handle.execute("""
                    INSERT INTO smp_aura_duel (event_id, duel_type, kit) VALUES
                        ('00000000-0000-0000-0000-000000000001', 'SWORD', NULL),
                        ('00000000-0000-0000-0000-000000000002', 'BOW', NULL),
                        ('00000000-0000-0000-0000-000000000003', NULL, 'Axe')
                    """);
        });

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "108");

        assertEquals(
                List.of("1 Sword", "2 Bow", "3 Axe"),
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT right(event_id::text, 1) || ' ' || kit FROM smp_aura_duel ORDER BY event_id")
                        .mapTo(String.class)
                        .list()),
                "a row an older smp wrote gets its kit from its type, and a row with a kit keeps it");
        assertEquals(
                List.of("event_id", "kit"),
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT column_name FROM information_schema.columns
                                WHERE table_schema = 'public' AND table_name = 'smp_aura_duel' ORDER BY column_name""").mapTo(String.class).list()),
                "the duel type column is gone");
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO smp_aura_event (id, discord_id, delta, reason)
                VALUES ('00000000-0000-0000-0000-000000000004', '100000000000000042', 10, 'DUEL_WIN')"""));
        final UnableToExecuteStatementException refused = assertThrows(
                UnableToExecuteStatementException.class,
                () -> jdbi.useHandle(handle -> handle.execute(
                        "INSERT INTO smp_aura_duel (event_id) VALUES ('00000000-0000-0000-0000-000000000004')")));
        assertEquals(
                "23502",
                ((SQLException) refused.getCause()).getSQLState(),
                "a duel record without a kit is refused by the kit's NOT NULL");
    }
}
