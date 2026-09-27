package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The season's dates, curves and phase, as the interface reads and writes them. */
class SeasonApiTest extends StewardUiTestSupport {

    /**
     * {@code which} decided between two dates with an {@code equals} and an {@code else}.
     *
     * So {@code "smpstart"}, {@code "launchh"} and a missing field all meant "launch", and the
     * interface answered 200 having overwritten the wrong one of the two dates a whole season
     * hangs off. A value outside the pair is a question this endpoint cannot answer, and the only
     * honest reply is a refusal.
     */
    @Test
    void aSeasonDateNeedsAName() throws Exception {
        final String at = "\"at\":\"2026-10-01T18:00:00Z\"";
        assertEquals(
                400,
                post("/api/season/date", "{" + at + ",\"which\":\"smpstart\"}").statusCode());
        assertEquals(400, post("/api/season/date", "{" + at + "}").statusCode());
        assertEquals(
                400, post("/api/season/date", "{" + at + ",\"which\":\"\"}").statusCode());
        // And the two it does know still work.
        assertEquals(
                200,
                post("/api/season/date", "{" + at + ",\"which\":\"smpStart\"}").statusCode());
        assertEquals(
                200,
                post("/api/season/date", "{" + at + ",\"which\":\"launch\"}").statusCode());
    }

    /**
     * "No date" is a real state - the start page and the MOTD countdown read it.
     *
     * A null {@code at} is the way back to it; a blank one is still a forgotten field, and
     * {@code which} is still required.
     */
    @Test
    void aSeasonDateCanBeRemoved() throws Exception {
        final String at = "\"at\":\"2026-10-01T18:00:00Z\"";
        assertEquals(
                200,
                post("/api/season/date", "{" + at + ",\"which\":\"launch\"}").statusCode());
        assertEquals(
                200,
                post("/api/season/date", "{" + at + ",\"which\":\"smpStart\"}").statusCode());

        assertEquals(
                400,
                post("/api/season/date", "{\"at\":\"  \",\"which\":\"launch\"}").statusCode());
        assertEquals(400, post("/api/season/date", "{\"at\":null}").statusCode());

        final HttpResponse<String> smp = post("/api/season/date", "{\"at\":null,\"which\":\"smpStart\"}");
        assertEquals(200, smp.statusCode(), smp.body());
        assertEquals(200, post("/api/season/date", "{\"which\":\"launch\"}").statusCode());

        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        assertFalse(season.has("launch"), season.toString());
        assertFalse(season.has("smpStart"), season.toString());
    }

    @Test
    void theCurvesAreRead() throws Exception {
        final JsonObject answer = GSON.fromJson(
                get("/api/metrics?subject=host&metric=cpu_percent&hours=6").body(), JsonObject.class);

        assertEquals("host", answer.get("subject").getAsString());
        assertTrue(answer.has("points"), answer.toString());
        // Nothing has sampled into this database, so the honest answer is no points, not a fabricated line.
        assertEquals(0, answer.getAsJsonArray("points").size());
    }

    @Test
    void aCurveNeedsAName() throws Exception {
        assertEquals(400, get("/api/metrics?subject=host").statusCode());
    }

    @Test
    void theSeasonIsReadable() throws Exception {
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        assertFalse(season.get("phase").getAsString().isBlank());
    }
}
