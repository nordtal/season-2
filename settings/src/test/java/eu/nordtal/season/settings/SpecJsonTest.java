package eu.nordtal.season.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import java.util.List;
import org.junit.jupiter.api.Test;

/** That a published schema says what each value names, at every depth a spec can hold one. */
class SpecJsonTest {

    @Test
    void aReferenceIsOnItsNodeInsideListsOfSpecsToo() {
        final JsonObject schema =
                JsonParser.parseString(SpecJson.schema(Track.class)).getAsJsonObject();

        final JsonObject role = child(schema, "role");
        assertEquals("DISCORD_CHANNEL", refers(role).get("to").getAsString());
        assertEquals(true, refers(role).get("optional").getAsBoolean());
        final JsonObject objective = child(child(schema, "objectives"), "subjects");
        assertEquals("SUBJECT", refers(objective).get("to").getAsString());
        assertEquals("statistic", refers(objective).get("dependsOn").getAsString());
        assertEquals(
                "STATISTIC",
                refers(child(child(schema, "objectives"), "statistic"))
                        .get("to")
                        .getAsString());
        assertFalse(child(child(schema, "objectives"), "key").has("refers"));
    }

    @Test
    void aStatisticNamesTheOnesItLeavesOut() {
        final JsonObject statistic = child(child(schema(), "objectives"), "statistic");

        assertEquals(
                List.of("minecraft:play_one_minute"),
                refers(statistic).getAsJsonArray("except").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertFalse(refers(child(child(schema(), "objectives"), "subjects")).has("except"));
    }

    @Test
    void aFieldSaysWhichValueOfASiblingItAppliesTo() {
        final JsonObject subjects = child(child(schema(), "objectives"), "subjects");

        final JsonObject applies = subjects.getAsJsonObject("appliesWhen");
        assertEquals("type", applies.get("key").getAsString());
        assertEquals(
                List.of("STATISTIC"),
                applies.getAsJsonArray("values").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertFalse(child(child(schema(), "objectives"), "key").has("appliesWhen"));
    }

    @Test
    void aClosedChoiceNamesWhereItsValuesAreNamedAndWhatEachIsDrawnWith() {
        final JsonObject choices = child(child(schema(), "objectives"), "type").getAsJsonObject("choices");

        assertEquals("example.objective-type", choices.get("names").getAsString());
        assertEquals(
                List.of("minecraft:chest", "minecraft:writable_book"),
                choices.getAsJsonArray("icons").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertEquals(2, choices.getAsJsonArray("values").size());
    }

    @Test
    void anIconForEveryValueOrForNone() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SpecJson.schema(Uneven.class));

        assertTrue(refused.getMessage().contains("kind"), refused.getMessage());
    }

    @Test
    void aListOfSpecsCarriesWhatANewEntryStartsFrom() {
        final JsonObject objectives = child(schema(), "objectives");

        final JsonObject blank = objectives.getAsJsonObject("defaults");
        assertEquals("STATISTIC", blank.get("type").getAsString());
        assertEquals("", blank.get("key").getAsString());
        assertEquals(0, blank.getAsJsonArray("subjects").size());
        assertFalse(schema().has("defaults"));
    }

    private static JsonObject schema() {
        return JsonParser.parseString(SpecJson.schema(Track.class)).getAsJsonObject();
    }

    private static JsonObject child(final JsonObject node, final String key) {
        return node.getAsJsonObject("children").getAsJsonObject(key);
    }

    private static JsonObject refers(final JsonObject node) {
        return node.getAsJsonObject("refers");
    }

    /** A spec naming a Discord role, and a list of objectives naming a statistic and what it counts. */
    @ConfigSpec
    interface Track {

        @Key("role")
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String role() {
            return "";
        }

        @Key("objectives")
        default List<Objective> objectives() {
            return List.of();
        }
    }

    /** One objective of the track. */
    @ConfigSpec
    interface Objective {

        @Key("key")
        default String key() {
            return "";
        }

        @Key("type")
        @AllowedValues({"HAND_IN", "STATISTIC"})
        @ChoiceNames(
                value = "example.objective-type",
                icons = {"minecraft:chest", "minecraft:writable_book"})
        default String type() {
            return "STATISTIC";
        }

        @Key("statistic")
        @Refers(value = Refers.To.STATISTIC, except = "minecraft:play_one_minute")
        @AppliesWhen(key = "type", values = "STATISTIC")
        default String statistic() {
            return "";
        }

        @Key("subjects")
        @Refers(value = Refers.To.SUBJECT, dependsOn = "statistic")
        @AppliesWhen(key = "type", values = "STATISTIC")
        default List<String> subjects() {
            return List.of();
        }
    }

    /** A choice with fewer icons than values. */
    @ConfigSpec
    interface Uneven {

        @Key("kind")
        @AllowedValues({"A", "B"})
        @ChoiceNames(value = "example.kind", icons = "minecraft:stone")
        default String kind() {
            return "A";
        }
    }
}
