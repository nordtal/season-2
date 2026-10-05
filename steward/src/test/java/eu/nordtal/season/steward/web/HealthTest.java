package eu.nordtal.season.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.steward.data.Data;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** The health route fails with a 503 when the database or steward-agent does not answer, so a plain check sees it. */
class HealthTest extends WebFixture {

    @Test
    void aDatabaseThatDoesNotAnswerIsA503() throws Exception {
        final HikariDataSource gone = new HikariDataSource();
        gone.close();
        final Data unanswered = new Data(new Database(gone, Jdbi.create(gone)), Clock.systemUTC());

        final JsonObject health = healthOf(newWeb(unanswered, new AgentClient(agent.client())));

        assertFalse(health.get("database").getAsBoolean(), health.toString());
        assertTrue(health.get("agent").getAsBoolean(), health.toString());
    }

    @Test
    void anAgentThatDoesNotAnswerIsA503() throws Exception {
        // Port 1 answers nothing on this machine.
        final AgentClient nobody =
                new AgentClient(new InternalClient(AgentWire.SERVICE, "http://127.0.0.1:1", "", Duration.ofSeconds(2)));

        final JsonObject health = healthOf(newWeb(data, nobody));

        assertTrue(health.get("database").getAsBoolean(), health.toString());
        assertFalse(health.get("agent").getAsBoolean(), health.toString());
    }

    /** Starts {@code web} on a free port, asks its health route once, and stops it again. */
    private static JsonObject healthOf(final Web web) throws Exception {
        try {
            final int port = web.start(0).port();
            final HttpResponse<String> answer = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(503, answer.statusCode(), answer.body());
            return GSON.fromJson(answer.body(), JsonObject.class);
        } finally {
            web.stop();
        }
    }
}
