package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that an admin's spawn regions and first join spawn are read under their new names after the upgrade. */
class SpawnSettingNamesUpgradeIntegrationTest {

    @Test
    void theSpawnsValuesAreCopiedToTheirNewPathsAndTheOldRowsStay() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "99");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO setting_override (service, name, path, actor_kind, changed) VALUES
                        ('smp', 'spawn', 'spawn-regions', 'HOST', now()),
                        ('smp', 'spawn', 'first-join-spawn.x', 'HOST', now()),
                        ('smp', 'spawn', 'first-join-spawn.world', 'HOST', now()),
                        ('smp', 'wheel', 'spawn-regions', 'HOST', now())
                    """);
            handle.execute("""
                    INSERT INTO setting_override_node
                        (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
                    SELECT v.service, v.name, v.path, n.node, n.parent, n.field, n.ordinal, n.kind, n.string,
                           n.number, n.flag
                    FROM (VALUES
                        ('smp', 'spawn', 'spawn-regions',
                            '[{"world": "nordtal", "min-x": 0, "max-x": 10}]'::jsonb),
                        ('smp', 'spawn', 'first-join-spawn.x', '3978.5'::jsonb),
                        ('smp', 'spawn', 'first-join-spawn.world', '"nordtal"'::jsonb),
                        ('smp', 'wheel', 'spawn-regions', '"another group"'::jsonb))
                        AS v (service, name, path, value),
                        setting_override_nodes(v.value) AS n
                    """);
        });

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "100");

        assertEquals(
                List.of(
                        "smp/spawn/first-join-spawn.world=\"nordtal\"",
                        "smp/spawn/first-join-spawn.x=3978.5",
                        "smp/spawn/nordtal-spawn.world=\"nordtal\"",
                        "smp/spawn/nordtal-spawn.x=3978.5",
                        "smp/spawn/protected-areas=[{\"max-x\": 10, \"min-x\": 0, \"world\": \"nordtal\"}]",
                        "smp/spawn/spawn-regions=[{\"max-x\": 10, \"min-x\": 0, \"world\": \"nordtal\"}]",
                        "smp/wheel/spawn-regions=\"another group\""),
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT service || '/' || name || '/' || path || '='
                                       || setting_override_value(service, name, path)::text
                                FROM setting_override ORDER BY service, name, path""").mapTo(String.class).list()),
                "the spawn's values are read under the new paths, the old rows stay and no other group is touched");
    }
}
