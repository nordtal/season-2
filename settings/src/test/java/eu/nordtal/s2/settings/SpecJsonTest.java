package eu.nordtal.s2.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
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

        @Key("statistic")
        @Refers(Refers.To.STATISTIC)
        default String statistic() {
            return "";
        }

        @Key("subjects")
        @Refers(value = Refers.To.SUBJECT, dependsOn = "statistic")
        default List<String> subjects() {
            return List.of();
        }
    }
}
