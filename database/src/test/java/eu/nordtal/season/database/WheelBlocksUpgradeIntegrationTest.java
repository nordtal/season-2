package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that an admin's wheel regions are read as wheel blocks, each its region's centre, after the upgrade. */
class WheelBlocksUpgradeIntegrationTest {

    @Test
    void eachWheelRegionBecomesItsCentreBlockAndTheOldRowStays() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "102");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO setting_override (service, name, path, actor_kind, changed) VALUES
                        ('smp', 'wheel', 'wheel-regions', 'HOST', now()),
                        ('smp', 'spawn', 'wheel-regions', 'HOST', now())
                    """);
            handle.execute("""
                    INSERT INTO setting_override_node
                        (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
                    SELECT v.service, v.name, v.path, n.node, n.parent, n.field, n.ordinal, n.kind, n.string,
                           n.number, n.flag
                    FROM (VALUES
                        ('smp', 'wheel', 'wheel-regions',
                            '[{"world": "nordtal", "min-x": 3978, "min-y": 75, "min-z": 2171,
                               "max-x": 3978, "max-y": 75, "max-z": 2171},
                              {"world": "nordtal_nether", "min-x": -5, "min-y": 60, "min-z": 10,
                               "max-x": 2, "max-y": 70, "max-z": 13},
                              {"world": "nordtal", "min-x": 4, "max-x": 8},
                              {"min-x": 1, "max-x": 1}]'::jsonb),
                        ('smp', 'spawn', 'wheel-regions', '"another group"'::jsonb))
                        AS v (service, name, path, value),
                        setting_override_nodes(v.value) AS n
                    """);
        });

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "103");

        assertEquals(
                List.of(
                        "smp/spawn/wheel-regions=\"another group\"",
                        "smp/wheel/wheel-blocks=[{\"x\": 3978, \"y\": 75, \"z\": 2171, \"world\": \"nordtal\"},"
                                + " {\"x\": -2, \"y\": 65, \"z\": 11, \"world\": \"nordtal_nether\"},"
                                + " {\"x\": 6, \"y\": 128, \"z\": 0, \"world\": \"nordtal\"}]",
                        "smp/wheel/wheel-regions=[{\"max-x\": 3978, \"max-y\": 75, \"max-z\": 2171, \"min-x\": 3978,"
                                + " \"min-y\": 75, \"min-z\": 2171, \"world\": \"nordtal\"}, {\"max-x\": 2, \"max-y\": 70,"
                                + " \"max-z\": 13, \"min-x\": -5, \"min-y\": 60, \"min-z\": 10, \"world\":"
                                + " \"nordtal_nether\"}, {\"max-x\": 8, \"min-x\": 4, \"world\": \"nordtal\"},"
                                + " {\"max-x\": 1, \"min-x\": 1}]"),
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT service || '/' || name || '/' || path || '='
                                       || setting_override_value(service, name, path)::text
                                FROM setting_override ORDER BY service, name, path""").mapTo(String.class).list()),
                "each region with a world is read as its centre block under wheel-blocks, the old row stays and no"
                        + " other group is touched");
    }
}
