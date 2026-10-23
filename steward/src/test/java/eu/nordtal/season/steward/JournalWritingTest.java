package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks that a changing route writes its journal line after it has answered, and only then. */
class JournalWritingTest extends WebTestSupport {

    @Test
    void aRunAskedForIsJournaledOnceByWhoAskedForIt() throws Exception {
        settleOpenRuns();
        final long before = count("SELECT count(*) FROM journal_entry WHERE action = 'REQUEST_RUN'");

        final HttpResponse<String> asked = post("/api/updates", "{\"kind\":\"BACKUP\"}");
        assertEquals(202, asked.statusCode(), asked.body());

        assertEquals(before + 1, count("SELECT count(*) FROM journal_entry WHERE action = 'REQUEST_RUN'"));
        // The stream shows the run once, as its own row under who asked for it.
        final long run = GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsLong();
        final List<JsonObject> items = GSON
                .fromJson(get("/api/journal?limit=200").body(), JsonObject.class)
                .getAsJsonArray("items")
                .asList()
                .stream()
                .map(JsonElement::getAsJsonObject)
                .filter(row ->
                        row.has("run") && row.getAsJsonObject("run").get("id").getAsLong() == run)
                .toList();
        assertEquals(1, items.size(), items.toString());
        assertEquals(
                "1", items.getFirst().getAsJsonObject("actor").get("person").getAsString(), items.toString());
        settleOpenRuns();
    }

    @Test
    void aRefusedRequestWritesNoLine() throws Exception {
        final long before = count("SELECT count(*) FROM journal_entry WHERE action = 'REQUEST_RUN'");

        final HttpResponse<String> refused = post("/api/updates", "{\"kind\":\"NOTHING\"}");
        assertEquals(400, refused.statusCode(), refused.body());

        assertEquals(before, count("SELECT count(*) FROM journal_entry WHERE action = 'REQUEST_RUN'"));
    }
}
