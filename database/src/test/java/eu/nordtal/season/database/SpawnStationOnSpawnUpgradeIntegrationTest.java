package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that a spawn station left on the field defaults moves to the station called Spawn, and a taken one stays. */
class SpawnStationOnSpawnUpgradeIntegrationTest {

    private static final String BEFORE = "133";
    private static final String AFTER = "134";

    @Test
    void theDefaultBlockMovesToTheStationCalledSpawnAsTheHost() {
        final Jdbi jdbi = upgradedWith(0, 64, 0, true);

        assertEquals(List.of("world nordtal", "x 3919", "y 82", "z 2204"), entry(jdbi));
        assertEquals(
                "HOST",
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT actor_kind FROM setting_override WHERE path = 'spawn-station'")
                        .mapTo(String.class)
                        .one()),
                "the host, not the admin who saved the defaults, set the block");
    }

    @Test
    void aBlockAnAdminTookStays() {
        assertEquals(List.of("world nordtal", "x 10", "y 70", "z -4"), entry(upgradedWith(10, 70, -4, true)));
    }

    @Test
    void theDefaultBlockStaysWithoutAStationCalledSpawn() {
        assertEquals(List.of("world nordtal", "x 0", "y 64", "z 0"), entry(upgradedWith(0, 64, 0, false)));
    }

    private static Jdbi upgradedWith(final int x, final int y, final int z, final boolean spawn) {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), BEFORE);
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO setting_override (service, name, path, actor_kind, actor_id, changed)
                    VALUES ('smp', 'rails', 'spawn-station', 'PERSON', '300000000000000077', now())""");
            handle.execute("""
                    INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number)
                    VALUES ('smp', 'rails', 'spawn-station', 0, NULL, NULL, NULL, 'LIST', NULL, NULL),
                           ('smp', 'rails', 'spawn-station', 1, 0, NULL, 0, 'OBJECT', NULL, NULL),
                           ('smp', 'rails', 'spawn-station', 2, 1, 'x', NULL, 'NUMBER', NULL, ?),
                           ('smp', 'rails', 'spawn-station', 3, 1, 'y', NULL, 'NUMBER', NULL, ?),
                           ('smp', 'rails', 'spawn-station', 4, 1, 'z', NULL, 'NUMBER', NULL, ?),
                           ('smp', 'rails', 'spawn-station', 5, 1, 'world', NULL, 'STRING', 'nordtal', NULL)""", x, y, z);
            handle.execute("""
                    INSERT INTO smp_rail_station (world, x, y, z, axis, name, named)
                    VALUES ('nordtal', 3938, 231, 2182, 'NORTH_SOUTH', 'spawn', now()),
                           ('nordtal_nether', 1, 70, 1, 'NORTH_SOUTH', 'Spawn', now())""");
            if (spawn) {
                handle.execute("""
                        INSERT INTO smp_rail_station (world, x, y, z, axis, name, named)
                        VALUES ('nordtal', 3919, 82, 2204, 'EAST_WEST', 'Spawn', now())""");
            }
        });
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), AFTER);
        return jdbi;
    }

    private static List<String> entry(final Jdbi jdbi) {
        return jdbi.withHandle(
                handle -> handle.createQuery("""
                        SELECT field || ' ' || coalesce(string, number::text) FROM setting_override_node
                        WHERE path = 'spawn-station' AND field IS NOT NULL ORDER BY field""").mapTo(String.class).list());
    }
}
