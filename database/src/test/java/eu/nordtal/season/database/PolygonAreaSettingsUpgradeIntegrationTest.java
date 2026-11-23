package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.setting.SettingStore;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that legacy cuboid overrides for duel areas and spawn protected areas migrate to sorted 3D polygons. */
class PolygonAreaSettingsUpgradeIntegrationTest {

    @Test
    void cuboidDuelAndSpawnOverridesMigrateToSorted3dPolygonsAndDropOldRows() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "142");
        Jdbi.create(upgraded.dataSource()).useHandle(PolygonAreaSettingsUpgradeIntegrationTest::seedLegacyOverrides);

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "144");

        final Map<String, String> overrides =
                SettingStore.using(upgraded.dataSource()).overrides(Set.of("smp")).stream()
                        .collect(Collectors.toMap(v -> v.group() + ":" + v.path(), SettingStore.Value::value));

        assertFalse(overrides.keySet().stream().anyMatch(k -> k.startsWith("duels:arena.")));
        assertFalse(overrides.keySet().stream().anyMatch(k -> k.startsWith("duels:negotiation-area.")));

        final JsonObject arena = singlePolygon(overrides.get("duels:arena"));
        assertEquals("Arena", arena.get("name").getAsString());
        assertEquals("nordtal", arena.get("world").getAsString());
        assertEquals(67, arena.get("min-y").getAsInt());
        assertEquals(82, arena.get("max-y").getAsInt());
        assertRectangle(arena.getAsJsonArray("corners"), 3960, 2157, 3980, 2173);

        final JsonObject negotiation = singlePolygon(overrides.get("duels:negotiation-area"));
        assertEquals("Negotiation", negotiation.get("name").getAsString());
        assertEquals("nordtal", negotiation.get("world").getAsString());
        assertEquals(68, negotiation.get("min-y").getAsInt());
        assertEquals(72, negotiation.get("max-y").getAsInt());
        assertRectangle(negotiation.getAsJsonArray("corners"), 100, 85, 105, 90);

        final JsonObject spawnArea = singlePolygon(overrides.get("spawn:protected-areas"));
        assertEquals("Area 1", spawnArea.get("name").getAsString());
        assertEquals("nordtal", spawnArea.get("world").getAsString());
        assertEquals(40, spawnArea.get("min-y").getAsInt());
        assertEquals(140, spawnArea.get("max-y").getAsInt());
        assertRectangle(spawnArea.getAsJsonArray("corners"), 10, 60, 50, 120);
    }

    private static void seedLegacyOverrides(final org.jdbi.v3.core.Handle handle) {
        // Reproduces dev.nordtal.eu: six arena.* rows with no arena.world and reversed min-z (2173) > max-z (2157).
        insertOverride(handle, "duels", "arena.min-x", "3960");
        insertOverride(handle, "duels", "arena.max-x", "3980");
        insertOverride(handle, "duels", "arena.min-y", "67");
        insertOverride(handle, "duels", "arena.max-y", "82");
        insertOverride(handle, "duels", "arena.min-z", "2173");
        insertOverride(handle, "duels", "arena.max-z", "2157");

        insertOverride(handle, "duels", "negotiation-area.world", "\"nordtal\"");
        insertOverride(handle, "duels", "negotiation-area.min-x", "105");
        insertOverride(handle, "duels", "negotiation-area.max-x", "100");
        insertOverride(handle, "duels", "negotiation-area.min-y", "72");
        insertOverride(handle, "duels", "negotiation-area.max-y", "68");
        insertOverride(handle, "duels", "negotiation-area.min-z", "90");
        insertOverride(handle, "duels", "negotiation-area.max-z", "85");

        insertOverride(handle, "spawn", "protected-areas", """
                [
                  {"world": "nordtal", "min-x": 10, "max-x": 50, "min-y": 40, "max-y": 140, "min-z": 120, "max-z": 60}
                ]
                """);
    }

    private static void insertOverride(
            final org.jdbi.v3.core.Handle handle, final String group, final String path, final String jsonValue) {
        handle.createUpdate("""
                INSERT INTO setting_override (service, name, path, actor_kind, changed)
                VALUES ('smp', :group, :path, 'STEWARD', now())
                """).bind("group", group).bind("path", path).execute();
        handle.createUpdate("""
                INSERT INTO setting_override_node
                    (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
                SELECT 'smp', :group, :path, n.*
                FROM setting_override_nodes(CAST(:json AS jsonb)) AS n
                """)
                .bind("group", group)
                .bind("path", path)
                .bind("json", jsonValue)
                .execute();
    }

    private static JsonObject singlePolygon(final String json) {
        assertNotNull(json, "migrated override row must exist");
        final JsonElement parsed = JsonParser.parseString(json);
        final JsonArray array = parsed.getAsJsonArray();
        assertEquals(1, array.size());
        return array.get(0).getAsJsonObject();
    }

    private static void assertRectangle(
            final JsonArray corners, final int x1, final int z1, final int x2, final int z2) {
        assertEquals(4, corners.size());
        assertCorner(corners.get(0).getAsJsonObject(), x1, z1);
        assertCorner(corners.get(1).getAsJsonObject(), x2, z1);
        assertCorner(corners.get(2).getAsJsonObject(), x2, z2);
        assertCorner(corners.get(3).getAsJsonObject(), x1, z2);
    }

    private static void assertCorner(final JsonObject corner, final int x, final int z) {
        assertEquals(x, corner.get("x").getAsInt());
        assertEquals(z, corner.get("z").getAsInt());
    }
}
