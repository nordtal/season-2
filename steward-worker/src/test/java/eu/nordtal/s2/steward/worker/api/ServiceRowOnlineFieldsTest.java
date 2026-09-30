package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.online.OnlinePlayer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        final Map<String, Object> row = row("smp", ServicesApi.Online.NONE);

        assertFalse(row.containsKey("players"), "unknown must not read as zero");
        assertFalse(row.containsKey("roster"), "unknown must not read as an empty list");
    }

    @Test
    void aGenuineZeroIsWrittenAsAZeroAndStillCarriesNoRoster() {
        final Map<String, Object> row = row("smp", new ServicesApi.Online(Map.of("smp", 0), Map.of()));

        assertEquals(0, row.get("players"), "nobody connected is a fact, and it is a number");
        assertFalse(row.containsKey("roster"), "there is nobody to list, so there is no list");
    }

    @Test
    void theListRidesAlongAsUuidAndNameInTheOrderItWasGiven() {
        final Map<String, Object> row = row(
                "smp",
                new ServicesApi.Online(
                        Map.of("smp", 2),
                        Map.of(
                                "smp",
                                List.of(
                                        new OnlinePlayer(ADA, "Ada", "smp", WHENEVER),
                                        new OnlinePlayer(BEN, "Ben", "smp", WHENEVER)))));

        assertEquals(2, row.get("players"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> roster = (List<Map<String, Object>>) row.get("roster");
        assertEquals(2, roster.size());
        assertEquals(
                ADA.toString(),
                roster.getFirst().get("uuid"),
                "the canonical 8-4-4-4-12 text, which is what a head service is asked with");
        assertEquals("Ada", roster.getFirst().get("name"));
        assertEquals(
                Map.of("uuid", BEN.toString(), "name", "Ben"),
                roster.get(1),
                "two fields and no others - `updated` and `subject` have both already been used");
    }

    @Test
    void theRosterOfOneServiceDoesNotLeakIntoAnothersRow() {
        final Map<String, Object> row = row(
                "limbo",
                new ServicesApi.Online(
                        Map.of("smp", 1, "limbo", 0),
                        Map.of("smp", List.of(new OnlinePlayer(ADA, "Ada", "smp", WHENEVER)))));

        assertEquals(0, row.get("players"));
        assertFalse(row.containsKey("roster"));
    }

    @Test
    void nothingElseOnTheRowIsTouched() {
        final Map<String, Object> row = row("smp", ServicesApi.Online.NONE);

        assertTrue(row.containsKey("service"), "putOnline adds fields, it does not build the row");
        assertEquals(1, row.size());
    }

    private static Map<String, Object> row(final String service, final ServicesApi.Online online) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        ServiceRows.putOnline(row, service, online);
        return row;
    }
}
