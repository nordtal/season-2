package eu.nordtal.season.common.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.json.Json;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    record Link(DiscordId discord, PlayerId player) {}

    @Test
    void anIdReadsAsTheTextItWraps() {
        assertEquals("123456789012345678", DiscordId.of("123456789012345678").toString());
        assertEquals("<@123>", "<@" + DiscordId.of("123") + ">");
    }

    @Test
    void anIdTravelsAsItsTextInJson() {
        final UUID uuid = UUID.fromString("0f3c6d1e-2b7a-4c55-9e0d-3f1a2b3c4d5e");
        final Link link = new Link(DiscordId.of("123"), PlayerId.of(uuid));

        final String json = Json.encode(link);

        assertEquals("{\"discord\":\"123\",\"player\":\"" + uuid + "\"}", json);
        assertEquals(link, Json.decode(json, Link.class));
    }

    @Test
    void aBlankDiscordIdIsNoId() {
        assertThrows(IllegalArgumentException.class, () -> DiscordId.of(" "));
    }
}
