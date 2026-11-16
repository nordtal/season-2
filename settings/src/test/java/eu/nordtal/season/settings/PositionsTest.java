package eu.nordtal.season.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.rule.SettingRules;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks that a position names its shape in the schema and that its world must be on the server. */
class PositionsTest {

    @Test
    void aPositionNamesItsShapeOnItsNodeAndOnAList() {
        final JsonObject children = schema(Places.class).getAsJsonObject("children");

        assertEquals("SPOT", children.getAsJsonObject("spawn").get("position").getAsString());
        assertEquals("BLOCK", children.getAsJsonObject("blocks").get("position").getAsString());
        assertFalse(children.getAsJsonObject("name").has("position"));
    }

    @Test
    void eachCoordinateOfAPositionIsBoundedAndOtherFieldsAreNot() {
        final Places outside = SpecJson.read(
                JsonParser.parseString("{\"spawn\": {\"world\": \"nordtal\", \"x\": 30000001, \"y\": -2049,"
                                + " \"z\": 0, \"yaw\": 181}, \"blocks\": []}")
                        .getAsJsonObject(),
                Places.class);

        final List<String> refused =
                SettingRules.breaches(SpecJson.schemaTree(Places.class), SpecJson.tree(outside, Places.class), Set.of())
                        .stream()
                        .map(SettingRules.Breach::setting)
                        .toList();

        assertEquals(List.of("spawn.x", "spawn.y", "spawn.yaw"), refused);
    }

    @Test
    void aPositionThatLacksAKeyOfItsShapeIsRefusedWhenTheSchemaIsBuilt() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SpecJson.schemaTree(Lacking.class));

        assertTrue(refused.getMessage().contains("has no yaw"), refused.getMessage());
    }

    @Test
    void aWorldTheServerDoesNotHaveIsRefusedAndNamesTheField() {
        final Places places = SpecJson.read(SpecJson.defaults(Places.class), Places.class);

        final SettingRefused refused = assertThrows(
                SettingRefused.class, () -> Positions.checkWorlds(Places.class, places, Set.of("nordtal")::contains));

        assertEquals("blocks.world", refused.setting());
        assertEquals(SettingRule.KNOWN, refused.rule());
        assertEquals("nether", refused.value());
    }

    @Test
    void worldsTheServerHasPass() {
        final Places places = SpecJson.read(SpecJson.defaults(Places.class), Places.class);

        assertDoesNotThrow(() -> Positions.checkWorlds(Places.class, places, Set.of("nordtal", "nether")::contains));
    }

    private static JsonObject schema(final Class<?> spec) {
        return JsonParser.parseString(SpecJson.schema(spec)).getAsJsonObject();
    }

    @ConfigSpec
    interface Places {

        @Key("name")
        default String name() {
            return "";
        }

        @Key("spawn")
        @Position(Position.Shape.SPOT)
        default Spot spawn() {
            return Specs.createUnsafe(Spot.class, Map.of());
        }

        @Key("blocks")
        @Position(Position.Shape.BLOCK)
        default List<Block> blocks() {
            return List.of(Specs.createUnsafe(Block.class, Map.of()));
        }
    }

    @ConfigSpec
    interface Spot {

        @Key("world")
        default String world() {
            return "nordtal";
        }

        @Key("x")
        default double x() {
            return 0.5;
        }

        @Key("y")
        default double y() {
            return 64;
        }

        @Key("z")
        default double z() {
            return 0.5;
        }

        @Key("yaw")
        default float yaw() {
            return 0;
        }
    }

    @ConfigSpec
    interface Block {

        @Key("world")
        default String world() {
            return "nether";
        }

        @Key("x")
        default int x() {
            return 0;
        }

        @Key("y")
        default int y() {
            return 64;
        }

        @Key("z")
        default int z() {
            return 0;
        }
    }

    @ConfigSpec
    interface Lacking {

        @Key("spot")
        @Position(Position.Shape.SPOT)
        default Block spot() {
            return Specs.createUnsafe(Block.class, Map.of());
        }
    }
}
