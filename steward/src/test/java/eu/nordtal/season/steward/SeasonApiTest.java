package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The season's dates, curves and phase, as the interface reads and writes them. */
class SeasonApiTest extends WebTestSupport {

    /** Any {@code which} outside the two dates is refused, rather than overwriting launch. */
    @Test
    void aSeasonDateNeedsAName() throws Exception {
        final String at = "\"at\":\"" + SOON + "\"";
        assertEquals(
                400,
                post("/api/season/date", "{" + at + ",\"which\":\"smpstart\"}").statusCode());
        assertEquals(400, post("/api/season/date", "{" + at + "}").statusCode());
        assertEquals(
                400, post("/api/season/date", "{" + at + ",\"which\":\"\"}").statusCode());
        // The two it does know still work.
        assertEquals(
                200,
                post("/api/season/date", "{" + at + ",\"which\":\"smpStart\"}").statusCode());
        assertEquals(
                200,
                post("/api/season/date", "{" + at + ",\"which\":\"launch\"}").statusCode());
    }

    /** A null {@code at} clears a date, a real state the start page reads; a blank one is still refused. */
    @Test
    void aSeasonDateCanBeRemoved() throws Exception {
        final String at = "\"at\":\"" + SOON + "\"";
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
                get("/api/metrics?subject=host&metric=cpu_percent&minutes=360").body(), JsonObject.class);

        assertEquals("host", answer.get("subject").getAsString());
        assertTrue(answer.has("points"), answer.toString());
        // Nothing has sampled into this database, so the answer is no points, not a fabricated line.
        assertEquals(0, answer.getAsJsonArray("points").size());
    }

    /** The page's shortest range is two minutes, which a whole number of hours cannot ask for. */
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

    @Test
    void theSeasonIsReadable() throws Exception {
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        assertFalse(season.get("phase").getAsString().isBlank());
    }
}
