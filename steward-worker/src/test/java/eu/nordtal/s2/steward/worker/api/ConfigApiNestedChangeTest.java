package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import eu.nordtal.s2.steward.worker.configfile.ConfigChange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A section can hold lists: a milestone's objectives, and each objective's items. What the browser
 * sends for those has to reach {@code ConfigFiles} as lists, not as their JSON text.
 */
class ConfigApiNestedChangeTest {

    @Test
    @DisplayName("a list of sections inside a section arrives as records, and a list of values as values")
    void nestedListsArriveAsLists() {
        final Map<String, ConfigChange> changes =
                ConfigApi.changesOf(JsonParser.parseString("""
                {"changes": {"milestones": [
                  {"key": "foothold", "objectives": [
                    {"key": "logs", "target": 64, "items": ["OAK_LOG", "SPRUCE_LOG"]},
                    {"key": "coal", "target": 32, "items": []}
                  ]},
                  {"key": "waiting", "objectives": []}
                ]}}
                """).getAsJsonObject());

        assertEquals(
                ConfigChange.sections(List.of(
                        Map.of(
                                "key",
                                "foothold",
                                "objectives",
                                List.of(
                                        Map.of(
                                                "key",
                                                "logs",
                                                "target",
                                                "64",
                                                "items",
                                                List.of("OAK_LOG", "SPRUCE_LOG")),
                                        Map.of("key", "coal", "target", "32", "items", List.of()))),
                        Map.of("key", "waiting", "objectives", List.of()))),
                changes.get("milestones"));
    }
}
