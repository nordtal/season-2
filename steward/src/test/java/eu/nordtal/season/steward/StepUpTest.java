package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests how long one step-up holds, which the server keeps per step-up, and ending it early. */
class StepUpTest extends WebTestSupport {

    @Test
    void aStepUpHoldsFifteenMinutesByDefault() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);

        assertEquals(Duration.ofMinutes(15), heldFor(browser));
    }

    @Test
    void aLongerHoldCountsForThatStepUpOnly() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);

        holdTheKey(browser, authenticator, "SIX_HOURS");
        assertEquals(Duration.ofHours(6), heldFor(browser));

        holdTheKey(browser, authenticator);
        assertEquals(Duration.ofMinutes(15), heldFor(browser));

        holdTheKey(browser, authenticator, "ONE_HOUR");
        assertEquals(Duration.ofHours(1), heldFor(browser));
    }

    @Test
    void aHoldTheServerDoesNotOfferIsRefused() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("credential", authenticator.assertion(started.body(), ORIGIN));
        envelope.addProperty("hold", "FOREVER");

        final HttpResponse<String> refused = post(browser, "/auth/webauthn/authenticate/finish", GSON.toJson(envelope));

        assertEquals(400, refused.statusCode(), refused.body());
    }

    @Test
    void theWindowIsTheServersToKeep() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator, "SIX_HOURS");
        settleOpenRuns();

        shiftStepUp(browser, Duration.ofHours(5));
        assertEquals(202, post(browser, "/api/updates", "{\"kind\":\"UPDATE\"}").statusCode());
        settleOpenRuns();

        shiftStepUp(browser, Duration.ofHours(2));
        assertEquals(403, post(browser, "/api/updates", "{\"kind\":\"UPDATE\"}").statusCode());
    }

    @Test
    void endingTheStepUpAsksAgainAtTheNextChangeOnly() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator, "ONE_HOUR");

        final HttpResponse<String> ended = delete(browser, "/api/step-up");
        assertEquals(204, ended.statusCode(), ended.body());

        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        assertEquals(false, me.has("stepUpUntil"), me.toString());
        assertEquals(200, get(browser, "/api/services").statusCode(), "reading still needs no new step-up");
        final HttpResponse<String> refused = post(browser, "/api/updates", "{\"kind\":\"UPDATE\"}");
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals(
                "SECOND_FACTOR_REQUIRED",
                GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString());
    }

    @Test
    void onlyARunThatReplacesOrStopsWhatRunsAsksForTheStepUp() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        settleOpenRuns();
        heldLongAgo(browser);

        for (final String kind : List.of("UPDATE", "DEPLOY", "RECREATE", "DOWN", "RESTORE", "REMOVE_PLUGIN")) {
            final HttpResponse<String> refused =
                    post(browser, "/api/updates", "{\"kind\":\"" + kind + "\",\"services\":[\"smp\"]}");
            assertEquals(403, refused.statusCode(), kind + ": " + refused.body());
        }
        for (final String kind : List.of("RESTART", "START", "BACKUP")) {
            final HttpResponse<String> asked =
                    post(browser, "/api/updates", "{\"kind\":\"" + kind + "\",\"services\":[\"smp\"]}");
            assertEquals(202, asked.statusCode(), kind + ": " + asked.body());
            settleOpenRuns();
        }
    }

    private static Duration heldFor(final HttpClient browser) throws Exception {
        final String csrf = csrfOf(browser);
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT extract(epoch FROM step_up_until - verified_at) FROM steward_session WHERE csrf = ?")) {
            statement.setString(1, csrf);
            try (var row = statement.executeQuery()) {
                row.next();
                return Duration.ofSeconds(Math.round(row.getDouble(1)));
            }
        }
    }

    private static void shiftStepUp(final HttpClient browser, final Duration by) throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.prepareStatement("UPDATE steward_session SET verified_at = verified_at"
                        + " - make_interval(secs => ?), step_up_until = step_up_until - make_interval(secs => ?)"
                        + " WHERE csrf = ?")) {
            statement.setLong(1, by.toSeconds());
            statement.setLong(2, by.toSeconds());
            statement.setString(3, csrfOf(browser));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String csrfOf(final HttpClient browser) throws Exception {
        return GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("csrf")
                .getAsString();
    }
}
