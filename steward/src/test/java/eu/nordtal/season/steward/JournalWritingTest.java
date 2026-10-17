package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The journal line a changing route writes once it has answered, and only then. */
class JournalWritingTest extends WebTestSupport {

    @Test
    void aRunAskedForIsJournaledOnceByWhoAskedForIt() throws Exception {
        settleOpenRuns();
        final long before = count("SELECT count(*) FROM journal_entry WHERE action = 'REQUEST_RUN'");

        final HttpResponse<String> asked = post("/api/updates", "{\"kind\":\"BACKUP\"}");
        assertEquals(202, asked.statusCode(), asked.body());

        assertEquals(before + 1, count("SELECT count(*) FROM journal_entry WHERE action = 'REQUEST_RUN'"));
        final JsonObject line = journalledBy("1", "REQUEST_RUN").getAsJsonObject("line");
        assertEquals("journal.request-run", line.get("key").getAsString(), line.toString());
        assertEquals(
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsLong(),
                line.getAsJsonObject("args").getAsJsonObject("run").get("value").getAsLong(),
                line.toString());
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
