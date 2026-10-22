package eu.nordtal.season.messages.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.value.DisplayName;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An editor's example fills a message's own placeholders from the admin's values, the schema and the kind. */
class DeclarationExampleTest {

    private static final MessageSchema.Bundle SCHEMA = MessageSchema.decode(new StringReader("""
            {"bundle": "test", "messages": [
              {"key": "greeting", "name": "Greeting", "section": [], "format": "MINIMESSAGE", "shown": ["CHAT"],
               "args": [
                 {"name": "player", "context": "player", "action": false},
                 {"name": "time", "kind": "duration", "example": "PT5M", "action": false},
                 {"name": "count", "kind": "number", "action": false},
                 {"name": "open", "action": true}]}],
             "contexts": {
               "player": {"name": "Player", "attributes": [{"name": "name", "kind": "name", "example": "Alex"}]},
               "network": {"name": "Network", "attributes": [{"name": "name", "kind": "text", "example": "Nordtal"}]}},
             "globals": [{"name": "network", "context": "network"}]}
            """));

    private static final Declaration GREETING =
            SCHEMA.declaration(SCHEMA.messages().getFirst());

    @Test
    void aGivenValueIsReadAsItsKind() {
        final Map<String, Object> example = GREETING.example(Map.of("player.name", "Sam", "count", "7"));

        assertEquals("Sam", ((DisplayName) example.get("player.name")).name());
        assertEquals(new BigDecimal("7"), example.get("count"));
    }

    @Test
    void aValueThatDoesNotReadAsItsKindIsTheSchemasExampleElseTheKinds() {
        final Map<String, Object> example = GREETING.example(Map.of("time", "a while", "count", "many"));

        assertEquals(Duration.ofMinutes(5), example.get("time"));
        assertEquals(new BigDecimal("3"), example.get("count"));
        assertEquals("Alex", ((DisplayName) example.get("player.name")).name());
    }

    @Test
    void aGlobalAndAnActionGetNoValue() {
        assertEquals(
                java.util.Set.of("player.name", "time", "count"),
                GREETING.example(Map.of("network.name", "Elsewhere")).keySet());
    }
}
