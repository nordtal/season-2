package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.online.OnlinePlayer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one rule {@code /api/services} has about these two fields, held where it can be held without
 * a Docker daemon: <b>a subject {@link ServicesApi} does not name produces no field at all</b> -
 * not {@code 0}, not {@code null}, not {@code []}.
 *
 * <p>It is two {@code null} checks in {@code WorkerApi#putOnline} and that is precisely why it is
 * tested: a later edit could make either one a {@code getOrDefault} and every other test in this
 * module would still pass, while the start page would start drawing "nobody is playing" over
 * "nobody has said" (steward/86, steward/111).
 */
class ServiceRowOnlineFieldsTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BEN = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Instant WHENEVER = Instant.parse("2026-09-17T12:00:00Z");

    @Test
    @DisplayName("a service nothing was written for carries neither field")
    void anUnknownServiceHasNoFields() {
        final Map<String, Object> row = row("smp", ServicesApi.Online.NONE);

        assertFalse(row.containsKey("players"), "unknown must not read as zero");
        assertFalse(row.containsKey("roster"), "unknown must not read as an empty list");
    }

    @Test
    @DisplayName("a genuine zero is written as a zero, and still carries no roster")
    void aQuietServiceHasACountAndNoList() {
        final Map<String, Object> row = row("smp", new ServicesApi.Online(Map.of("smp", 0), Map.of()));

        assertEquals(0, row.get("players"), "nobody connected is a fact, and it is a number");
        assertFalse(row.containsKey("roster"), "there is nobody to list, so there is no list");
    }

    @Test
    @DisplayName("the list rides along as uuid and name, in the order it was given")
    void aBusyServiceCarriesItsPeople() {
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
    @DisplayName("the roster of one service does not leak into another's row")
    void anotherServicesPeopleStayThere() {
        final Map<String, Object> row = row(
                "limbo",
                new ServicesApi.Online(
                        Map.of("smp", 1, "limbo", 0),
                        Map.of("smp", List.of(new OnlinePlayer(ADA, "Ada", "smp", WHENEVER)))));

        assertEquals(0, row.get("players"));
        assertFalse(row.containsKey("roster"));
    }

    @Test
    @DisplayName("nothing else on the row is touched")
    void theRestOfTheRowIsLeftAlone() {
        final Map<String, Object> row = row("smp", ServicesApi.Online.NONE);

        assertTrue(row.containsKey("service"), "putOnline adds fields, it does not build the row");
        assertEquals(1, row.size());
    }

    private static Map<String, Object> row(final String service, final ServicesApi.Online online) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        WorkerApi.putOnline(row, service, online);
        return row;
    }
}
