package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class BorderRegionsUpgradeIntegrationTest {

    private static final String OLD_MILESTONES = """
            [
              {"key": "departure", "unlocks": "BORDER", "border-diameter": 43, "admin-unlocked": true, "objectives": []},
              {"key": "nether", "unlocks": "NETHER", "border-diameter": 0, "admin-unlocked": false, "objectives": []},
              {"key": "exploration", "unlocks": " border ", "border-diameter": 5000, "admin-unlocked": false, "objectives": []}
            ]
            """;

    private static final String OLD_WORLDS = """
            [
              {
                "world": "nordtal",
                "enabled": true,
                "polygons": [
                  {"name": "Spawn", "corners": [{"x": 3969, "z": 2178}, {"x": 3989, "z": 2178}, {"x": 3989, "z": 2198}, {"x": 3969, "z": 2198}]}
                ]
              }
            ]
            """;

    @Test
    void storedMilestonesAndCustomBordersGainRegionsAndDimensionSquaresWhileKeepingSpawn() {
        final var database = TestDatabase.empty();
        final var placeholders = DatabaseRole.placeholders(DatabaseRole.PREFIX);
        database.migrate(placeholders, "142");
        final var jdbi = Jdbi.create(database.dataSource());

        jdbi.useHandle(handle -> {
            storeOverride(handle, "smp", "milestones", "milestones", OLD_MILESTONES);
            storeOverride(handle, "smp", "custom-border", "worlds", OLD_WORLDS);
        });

        database.migrate(placeholders, "143");

        final JsonArray milestones = readArray(jdbi, "milestones", "milestones");
        final JsonObject departure = milestones.get(0).getAsJsonObject();
        assertFalse(departure.has("border-diameter"));
        assertEquals(
                List.of("departure"),
                departure.getAsJsonArray("border-regions").asList().stream()
                        .map(element -> element.getAsString())
                        .toList());
        final JsonObject nether = milestones.get(1).getAsJsonObject();
        assertFalse(nether.has("border-diameter"));
        assertTrue(nether.getAsJsonArray("border-regions").isEmpty());
        final JsonObject exploration = milestones.get(2).getAsJsonObject();
        assertFalse(exploration.has("border-diameter"));
        assertEquals(
                List.of("exploration"),
                exploration.getAsJsonArray("border-regions").asList().stream()
                        .map(element -> element.getAsString())
                        .toList());

        final JsonArray worlds = readArray(jdbi, "custom-border", "worlds");
        assertEquals(3, worlds.size());
        final JsonArray overworldPolygons = worlds.get(0).getAsJsonObject().getAsJsonArray("polygons");
        final List<String> names = new ArrayList<>();
        overworldPolygons.forEach(
                polygon -> names.add(polygon.getAsJsonObject().get("name").getAsString()));
        assertEquals(
                List.of("Spawn", "departure", "foothold", "settlement", "expanse", "frontier", "exploration"), names);
        final JsonArray extraCorners =
                overworldPolygons.get(6).getAsJsonObject().getAsJsonArray("corners");
        assertEquals(4, extraCorners.size());
        extraCorners.forEach(corner -> {
            final int x = corner.getAsJsonObject().get("x").getAsInt();
            final int z = corner.getAsJsonObject().get("z").getAsInt();
            assertTrue(x >= 160 && x <= 10079, "placeholder x inside overworld bounds");
            assertTrue(z >= 160 && z <= 10079, "placeholder z inside overworld bounds");
        });
        assertEquals(
                "nordtal_nether", worlds.get(1).getAsJsonObject().get("world").getAsString());
        assertEquals(
                "nordtal_the_end", worlds.get(2).getAsJsonObject().get("world").getAsString());
    }

    private static JsonArray readArray(final Jdbi jdbi, final String name, final String path) {
        return JsonParser.parseString(jdbi.withHandle(
                        handle -> handle.createQuery("SELECT setting_override_value('smp', :name, :path)::text")
                                .bind("name", name)
                                .bind("path", path)
                                .mapTo(String.class)
                                .one()))
                .getAsJsonArray();
    }

    private static void storeOverride(
            final org.jdbi.v3.core.Handle handle,
            final String service,
            final String name,
            final String path,
            final String json) {
        handle.execute(
                "INSERT INTO setting_override (service, name, path, actor_kind, actor_id, changed)"
                        + " VALUES (?, ?, ?, 'STEWARD', NULL, now())",
                service,
                name,
                path);
        handle.execute(
                "INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)"
                        + " SELECT ?, ?, ?, n.* FROM setting_override_nodes(?::jsonb) AS n",
                service,
                name,
                path,
                json);
    }
}
