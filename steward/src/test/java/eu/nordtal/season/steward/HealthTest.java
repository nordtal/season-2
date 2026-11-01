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
import java.util.concurrent.atomic.AtomicBoolean;
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
    void theGameRouteFollowsTheLastPing() throws Exception {
        final AtomicBoolean answers = new AtomicBoolean(false);
        final Web web = newWeb(data, new AgentClient(agent.client()), answers::get);
        try {
            final int port = web.start(0).port();

            assertFalse(answerOf(port, "/api/health/game", 503).get("game").getAsBoolean());
            answers.set(true);
            assertTrue(answerOf(port, "/api/health/game", 200).get("game").getAsBoolean());
        } finally {
            web.stop();
        }
    }

    private static JsonObject healthOf(final Web web) throws Exception {
        try {
            return answerOf(web.start(0).port(), "/api/health", 503);
        } finally {
            web.stop();
        }
    }

    /** Asks until the route gives the status, because the game ping runs on the scheduler every 15 seconds. */
    private static JsonObject answerOf(final int port, final String path, final int status) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .build();
        final long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpResponse<String> answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        while (answer.statusCode() != status && System.nanoTime() < until) {
            Thread.sleep(200);
            answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(status, answer.statusCode(), answer.body());
        return GSON.fromJson(answer.body(), JsonObject.class);
    }
}
