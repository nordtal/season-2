package eu.nordtal.s2.internalapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The guard every internal service shares: no secret, no service, and no route but health without it. */
class InternalServerTest {

    private static final InternalServer SERVER = new InternalServer(
            "steward-bunq", Map.of("NORDTAL_STEWARD_BUNQ_PORT", " 8090 ", "NORDTAL_STEWARD_BUNQ_EMPTY", "  ")::get);

    private Javalin running;

    @AfterEach
    void stop() {
        if (running != null) {
            running.stop();
        }
    }

    @Test
    void aServiceWithoutItsSecretDoesNotStart() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SERVER.create(" ", config -> {}));
        assertTrue(
                refused.getMessage().contains("NORDTAL_STEWARD_BUNQ_TOKEN"),
                "the refusal names the variable to set: " + refused.getMessage());
    }

    @Test
    void settingsAreTheServicesOwnVariables() {
        assertEquals("NORDTAL_STEWARD_BUNQ_ACCOUNT_ID", SERVER.variable("ACCOUNT_ID"));
        assertEquals("8090", SERVER.setting("PORT", "1"));
        assertEquals("fallback", SERVER.setting("EMPTY", "fallback"), "blank is unset");
        assertEquals("fallback", SERVER.setting("MISSING", "fallback"));
    }

    @Test
    void onlyHealthAnswersWithoutTheSecret() {
        running = SERVER.create("the-secret", config -> config.routes.get("/api/thing", ctx -> ctx.result("thing")))
                .start("127.0.0.1", 0);
        final String base = "http://127.0.0.1:" + running.port();

        final InternalClient stranger = new InternalClient("steward-bunq", base, "a-guess", Duration.ofSeconds(5));
        assertTrue(stranger.isReachable(), "health is open, so steward can ask whether the service is up");
        assertEquals(
                401,
                assertThrows(InternalClient.Failure.class, () -> stranger.get("/api/thing"))
                        .status());

        final InternalClient steward = new InternalClient("steward-bunq", base, "the-secret", Duration.ofSeconds(5));
        assertEquals("thing", steward.get("/api/thing"));
    }
}
