package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.database.online.OnlinePlayer;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.steward.WireJson;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A subject {@link ServicesApi} does not name gets no online fields at all, not even {@code 0} or {@code null}.
 *
 * Otherwise the start page would draw "nobody is playing" where it should draw "nobody has said".
 */
class ServiceRowOnlineFieldsTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BEN = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Instant WHENEVER = Instant.parse("2026-09-17T12:00:00Z");

    @Test
    void aServiceNothingWasWrittenForCarriesNeitherField() {
        final JsonObject row = row("smp", ServicesApi.Online.NONE);

        assertFalse(row.has("players"), "unknown must not read as zero");
        assertFalse(row.has("roster"), "unknown must not read as an empty list");
    }

    @Test
    void aGenuineZeroIsWrittenAsAZeroAndStillCarriesNoRoster() {
        final JsonObject row = row("smp", new ServicesApi.Online(Map.of("smp", 0), Map.of()));

        assertEquals(0, row.get("players").getAsInt(), "nobody connected is a fact, and it is a number");
        assertFalse(row.has("roster"), "there is nobody to list, so there is no list");
    }

    @Test
    void theListRidesAlongAsUuidAndNameInTheOrderItWasGiven() {
        final JsonObject row = row(
                "smp",
                new ServicesApi.Online(
                        Map.of("smp", 2),
                        Map.of(
                                "smp",
                                List.of(
                                        new OnlinePlayer(ADA, "Ada", "smp", WHENEVER),
                                        new OnlinePlayer(BEN, "Ben", "smp", WHENEVER)))));

        assertEquals(2, row.get("players").getAsInt());
        final JsonArray roster = row.getAsJsonArray("roster");
        assertEquals(2, roster.size());
        assertEquals(
                ADA.toString(),
                roster.get(0).getAsJsonObject().get("uuid").getAsString(),
                "the canonical 8-4-4-4-12 text, which is what a head service is asked with");
        assertEquals("Ada", roster.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(
                Set.of("uuid", "name"),
                roster.get(1).getAsJsonObject().keySet(),
                "two fields and no others - `updated` and `subject` have both already been used");
    }

    @Test
    void theRosterOfOneServiceDoesNotLeakIntoAnothersRow() {
        final JsonObject row = row(
                "limbo",
                new ServicesApi.Online(
                        Map.of("smp", 1, "limbo", 0),
                        Map.of("smp", List.of(new OnlinePlayer(ADA, "Ada", "smp", WHENEVER)))));

        assertEquals(0, row.get("players").getAsInt());
        assertFalse(row.has("roster"));
    }

    private static JsonObject row(final String service, final ServicesApi.Online online) {
        return wireRow(service, online, new AgentWire.Topology(List.of(), List.of()));
    }

    /** The row as the browser receives it, of a stopped container nothing else is known about. */
    static JsonObject wireRow(
            final String service, final ServicesApi.Online online, final AgentWire.Topology topology) {
        final AgentWire.Container container =
                new AgentWire.Container(service, "id-" + service, null, null, "exited", null, null, null, null, null);
        return WireJson.gson()
                .toJsonTree(ServiceRows.describe(
                        container, new ImageResult(true, Map.of(), Set.of(), null), online, Map.of(), topology))
                .getAsJsonObject();
    }
}
