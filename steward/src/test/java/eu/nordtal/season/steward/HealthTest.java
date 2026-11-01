package eu.nordtal.season.steward;

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

/** Checks that the health routes answer 503 when the database, steward-agent or the game does not answer. */
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
        final AgentClient nobody =
                new AgentClient(new InternalClient(AgentWire.SERVICE, "http://127.0.0.1:1", "", Duration.ofSeconds(2)));

        final JsonObject health = healthOf(newWeb(data, nobody));

        assertTrue(health.get("database").getAsBoolean(), health.toString());
        assertFalse(health.get("agent").getAsBoolean(), health.toString());
    }

    @Test
    void noProxyAnsweringIsA503() throws Exception {
        final JsonObject health =
                healthOf(newWeb(data, new AgentClient(agent.client()), () -> false), "/api/health/game", 503);

        assertFalse(health.get("game").getAsBoolean(), health.toString());
    }

    @Test
    void aProxyAnsweringIsA200() throws Exception {
        final JsonObject health =
                healthOf(newWeb(data, new AgentClient(agent.client()), () -> true), "/api/health/game", 200);

        assertTrue(health.get("game").getAsBoolean(), health.toString());
    }

    private static JsonObject healthOf(final Web web) throws Exception {
        return healthOf(web, "/api/health", 503);
    }

    private static JsonObject healthOf(final Web web, final String path, final int status) throws Exception {
        try {
            final int port = web.start(0).port();
            final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .build();
            // The game ping runs on the scheduler, so its first answer may come a moment after the start.
            final long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            HttpResponse<String> answer;
            do {
                answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            } while (answer.statusCode() != status && System.nanoTime() < until);
            assertEquals(status, answer.statusCode(), answer.body());
            return GSON.fromJson(answer.body(), JsonObject.class);
        } finally {
            web.stop();
        }
    }
}
