package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.database.inbox.StewardRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Tests the Router's season routes and the curves as the interface reads and writes them. */
class SeasonApiTest extends WebTestSupport {

    @Test
    void theRouterIsReadable() throws Exception {
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        assertFalse(season.get("opening").getAsString().isBlank(), season.toString());
        assertFalse(season.get("mode").getAsString().isBlank(), season.toString());
        assertTrue(season.has("maintenance"), season.toString());

        final JsonObject players = GSON.fromJson(get("/api/season/players").body(), JsonObject.class);
        assertEquals(0, players.get("members").getAsInt(), players.toString());
    }

    /** A date that has passed is the database's refusal, and one that is no instant is the route's. */
    @Test
    void aLaunchDateLiesAhead() throws Exception {
        final HttpResponse<String> past = post("/api/season/launch", "{\"at\":\"2020-01-01T00:00:00Z\"}");
        assertEquals(409, past.statusCode(), past.body());
        assertEquals(
                "IN_THE_PAST",
                GSON.fromJson(past.body(), JsonObject.class).get("code").getAsString());
        assertEquals(400, post("/api/season/launch", "{\"at\":\"tomorrow\"}").statusCode());
        assertEquals(400, post("/api/season/launch", "{}").statusCode());

        final HttpResponse<String> set =
                post("/api/season/launch", "{\"at\":\"" + SOON + "\",\"reason\":\"the trailer is out\"}");
        assertEquals(200, set.statusCode(), set.body());
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        assertEquals(SOON, season.get("launch").getAsString());
        assertEquals("COUNTDOWN", season.get("opening").getAsString());

        final HttpResponse<String> closed = post("/api/season/close", "{}");
        assertEquals(409, closed.statusCode(), closed.body());
        assertEquals(
                "NOT_LAUNCHED",
                GSON.fromJson(closed.body(), JsonObject.class).get("code").getAsString());
    }

    @Test
    void maintenanceNamesItsSide() throws Exception {
        assertEquals(400, post("/api/season/maintenance", "{}").statusCode());
        try {
            assertEquals(200, post("/api/season/maintenance", "{\"on\":true}").statusCode());
            final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
            assertTrue(season.get("maintenance").getAsBoolean(), season.toString());
            assertTrue(season.getAsJsonObject("lastMaintenance").has("on"), season.toString());
        } finally {
            post("/api/season/maintenance", "{\"on\":false}");
        }
    }

    @Test
    void aSwitchIsARunWithItsReason() throws Exception {
        final SeasonMode active = data.season().state().mode();
        assertEquals(400, post("/api/season/switch", "{}").statusCode());
        assertEquals(
                400, post("/api/season/switch", "{\"target\":\"CREATIVE\"}").statusCode());
        assertRefused(post("/api/season/switch", target(active, "")), "SWITCH_TO_ACTIVE");
        try {
            final HttpResponse<String> asked = post("/api/season/switch", target(active.other(), "the round is over"));
            assertEquals(202, asked.statusCode(), asked.body());
            final JsonObject run = GSON.fromJson(asked.body(), JsonObject.class).getAsJsonObject("run");
            assertEquals("SWITCH_MODE", run.get("kind").getAsString());
            assertEquals(
                    new StewardRequest.SwitchMode(active.other(), "the round is over"),
                    data.updates().requestOf(run.get("id").getAsLong()).orElseThrow());

            assertRefused(post("/api/season/switch", target(active, "")), "SWITCH_OPEN");
            final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
            assertEquals(
                    "the round is over",
                    season.getAsJsonObject("lastSwitch").get("reason").getAsString());
        } finally {
            settleOpenRuns();
        }
    }

    /** Try again is a switch to the active mode, and only a failed switch makes room for it. */
    @Test
    void tryAgainFollowsAFailedSwitchOnly() throws Exception {
        final SeasonMode active = data.season().state().mode();
        try {
            final HttpResponse<String> asked = post("/api/season/switch", target(active.other(), ""));
            assertEquals(202, asked.statusCode(), asked.body());
            final long id = GSON.fromJson(asked.body(), JsonObject.class)
                    .getAsJsonObject("run")
                    .get("id")
                    .getAsLong();
            sql("UPDATE steward_inbox SET status = 'FAILED', started = now(), finished = now() WHERE id = " + id);

            final HttpResponse<String> again = post("/api/season/switch", target(active, ""));
            assertEquals(202, again.statusCode(), again.body());
        } finally {
            settleOpenRuns();
        }
        assertRefused(post("/api/season/switch", target(active, "")), "SWITCH_TO_ACTIVE");
    }

    @Test
    void aSwitchIsNoOrdinaryRun() throws Exception {
        assertEquals(400, post("/api/updates", "{\"kind\":\"SWITCH_MODE\"}").statusCode());
    }

    private static String target(final SeasonMode mode, final String reason) {
        return "{\"target\":\"" + mode.name() + "\",\"reason\":\"" + reason + "\"}";
    }

    private static void assertRefused(final HttpResponse<String> answer, final String code) {
        assertEquals(409, answer.statusCode(), answer.body());
        assertEquals(
                code, GSON.fromJson(answer.body(), JsonObject.class).get("code").getAsString());
    }

    @Test
    void theCurvesAreRead() throws Exception {
        final JsonObject answer = GSON.fromJson(
                get("/api/metrics?subject=host&metric=cpu_percent&minutes=360").body(), JsonObject.class);

        assertEquals("host", answer.get("subject").getAsString());
        assertTrue(answer.has("points"), answer.toString());
        assertEquals(0, answer.getAsJsonArray("points").size());
    }

    /** The shortest range of the page is two minutes, which a whole number of hours cannot ask for. */
    @Test
    void aCurveSpansTheMinutesAsked() throws Exception {
        final Instant before = Instant.now();
        final JsonObject answer = GSON.fromJson(
                get("/api/metrics?subject=host&metric=cpu_percent&minutes=2").body(), JsonObject.class);

        final Instant from = Instant.parse(answer.get("from").getAsString());
        assertFalse(from.isBefore(before.minus(Duration.ofMinutes(2)).minusSeconds(5)), from.toString());
        assertFalse(from.isAfter(Instant.now().minus(Duration.ofMinutes(2))), from.toString());
    }

    @Test
    void aCurveNeedsAName() throws Exception {
        assertEquals(400, get("/api/metrics?subject=host").statusCode());
    }
}
