package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** Holds what the contract half of the bot's registration does to a database that still holds the legacy shape. */
class RegistrationShapeUpgradeIntegrationTest {

    @Test
    void theLegacyTeamTablesAndOpenRoundsGoAndTheRegistrationStays() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "30");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO registration (id, game, state) VALUES
                    ('00000000-0000-0000-0000-000000000001', 'hunger-games', 'OPEN'),
                    ('00000000-0000-0000-0000-000000000002', 'hunger-games', 'ENDED');
                INSERT INTO hg_game (id, registration_id, state) VALUES
                    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'REGISTRATION'),
                    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'DECIDED')
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "31");

        assertAll(
                () -> assertEquals(List.of("DECIDED"), strings(jdbi, "SELECT state FROM hg_game")),
                () -> assertEquals(
                        List.of("ENDED", "OPEN"), strings(jdbi, "SELECT state FROM registration ORDER BY state")),
                () -> assertEquals(
                        List.of(),
                        strings(
                                jdbi,
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_name IN ('hg_team', 'hg_member')")),
                () -> assertThrows(
                        UnableToExecuteStatementException.class,
                        () -> jdbi.useHandle(handle -> handle.execute("INSERT INTO hg_game (registration_id, state)"
                                + " VALUES ('00000000-0000-0000-0000-000000000001', 'REGISTRATION')")),
                        "REGISTRATION is no state of a game"));
    }

    private static List<String> strings(final Jdbi jdbi, final String sql) {
        return jdbi.withHandle(
                handle -> handle.createQuery(sql).mapTo(String.class).list());
    }
}
