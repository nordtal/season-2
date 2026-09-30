package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The proxy in front of steward-worker: the token it adds, health, and the command run rows. */
class WorkerProxyTest extends StewardUiTestSupport {

    @Test
    void theWorkersAnswerIsNotRewritten() throws Exception {
        final HttpResponse<String> response = get("/api/services");

        assertEquals(200, response.statusCode());
        final JsonArray services = GSON.fromJson(response.body(), JsonArray.class);
        assertEquals("smp", services.get(0).getAsJsonObject().get("service").getAsString());
        assertTrue(services.get(0).getAsJsonObject().get("hasConsole").getAsBoolean());
    }

    @Test
    void theCookieAloneIsNotEnough() throws Exception {
        // A form posted from another site carries the cookie. It cannot read /api/me.
        final HttpResponse<String> refused = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/api/services/smp/console"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"list\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, refused.statusCode(), refused.body());
    }

    @Test
    void theTokenFromMeWorks() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final String csrf = me.get("csrf").getAsString();

        final HttpResponse<String> accepted = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/api/services/smp/console"))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"list\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, accepted.statusCode(), accepted.body());
    }

    @Test
    void aBrokenWorkerIsASentence() throws Exception {
        workerBroken.set(true);
        try {
            final HttpResponse<String> response = get("/api/services");

            assertEquals(502, response.statusCode());
            final JsonObject error = GSON.fromJson(response.body(), JsonObject.class);
            assertEquals(
                    "steward-worker",
                    error.get("where").getAsString(),
                    "an empty table would look like a stack with nothing running");
            assertFalse(error.get("error").getAsString().isBlank());
        } finally {
            workerBroken.set(false);
        }
    }

    @Test
    void healthIsOpen() throws Exception {
        final JsonObject health = GSON.fromJson(get(browser(), "/api/health").body(), JsonObject.class);

        assertEquals("ok", health.get("status").getAsString());
        assertTrue(health.get("worker").getAsBoolean(), "the fake worker is up");
    }

    /**
     * A {@code HEAD} against the health route answers like its {@code GET}, which is what a monitor sends first.
     *
     * The gate looks up roles by exact method, so an unrouted {@code HEAD} would read as undecided.
     */
    @Test
    void headOnHealthIsNotUndecided() throws Exception {
        final HttpResponse<Void> head = browser()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/api/health"))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.discarding());

        assertEquals(200, head.statusCode(), "HEAD on an ANYONE route must not be refused as undecided");
    }

    @Test
    void anEmptyQueryIsNotForwardedAsTheWordNull() {
        // "?null" reaches the worker as a parameter named null with no value.
        assertEquals("", StewardUi.forwardedQuery(null));
        assertEquals("", StewardUi.forwardedQuery(" "));
        assertEquals("?tail=1000", StewardUi.forwardedQuery("tail=1000"));
    }

    @Test
    void askingIsARow() throws Exception {
        settleOpenRuns();
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final HttpResponse<String> asked = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/api/updates"))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"BACKUP\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, asked.statusCode(), asked.body());
        final JsonObject row = GSON.fromJson(asked.body(), JsonObject.class);
        assertEquals("BACKUP", row.get("kind").getAsString());
        assertEquals("PENDING", row.get("status").getAsString());
        // Who asked is written down, as the person's Discord id.
        assertEquals("PERSON", row.get("actorKind").getAsString(), row.toString());
        assertEquals("1", row.get("actorId").getAsString(), row.toString());

        // It is in the list the interface draws its runs from.
        final JsonArray recent = GSON.fromJson(get("/api/updates").body(), JsonArray.class);
        assertTrue(recent.size() >= 1);
        assertEquals(
                row.get("id").getAsLong(),
                recent.get(0).getAsJsonObject().get("id").getAsLong());
    }

    @Test
    void aSecondPressIsRefused() throws Exception {
        settleOpenRuns();
        final HttpResponse<String> first = post("/api/updates", "{\"kind\":\"DOWN\",\"services\":[\"smp\"]}");
        assertEquals(202, first.statusCode(), first.body());
        final long id = GSON.fromJson(first.body(), JsonObject.class).get("id").getAsLong();

        final HttpResponse<String> second = post("/api/updates", "{\"kind\":\"DOWN\",\"services\":[\"smp\"]}");

        assertEquals(409, second.statusCode(), second.body());
        assertTrue(second.body().contains("Run #" + id + " is still pending"), second.body());
        settleOpenRuns();
    }

    @Test
    void theActiveRunCarriesItsScope() throws Exception {
        settleOpenRuns();
        final JsonObject none = GSON.fromJson(get("/api/updates/active").body(), JsonObject.class);
        assertTrue(none.has("run") && none.get("run").isJsonNull(), none.toString());

        final HttpResponse<String> asked = post("/api/updates", "{\"kind\":\"DOWN\",\"services\":[\"smp\"]}");
        assertEquals(202, asked.statusCode(), asked.body());
        assertEquals(
                "[\"smp\"]",
                GSON.fromJson(asked.body(), JsonObject.class).get("scope").toString());

        final JsonObject run = GSON.fromJson(get("/api/updates/active").body(), JsonObject.class)
                .getAsJsonObject("run");
        assertEquals("DOWN", run.get("kind").getAsString());
        assertEquals("[\"smp\"]", run.get("scope").toString());
        settleOpenRuns();
    }

    @Test
    void nonsenseIsNotARun() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final HttpResponse<String> refused = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/api/updates"))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"DELETE_EVERYTHING\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, refused.statusCode(), refused.body());
    }

    /** A grant of six million years is refused as a slip of the keyboard, capped at a decade. */
    @Test
    void anAbsurdGrantIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/access/grant", "{\"discordId\":\"1\",\"days\":2147483647}");
        assertEquals(400, refused.statusCode(), refused.body());

        // 3650 is a decade of free access, one keystroke away from 365.
        final HttpResponse<String> decade = post("/api/access/grant", "{\"discordId\":\"1\",\"days\":3650}");
        assertEquals(400, decade.statusCode(), decade.body());
        assertTrue(decade.body().contains("between 1 and 365 days"), decade.body());
    }

    /**
     * A grant from here is an {@code access_request} row the bot carries out, answered 202 with its id.
     *
     * Only the bot sets the role, messages the member and journals, so this side writes no journal line.
     */
    @Test
    void accessChangesAskTheBot() throws Exception {
        final long journalBefore = count("select count(*) from audit_log");
        final String[][] asks = {
            {
                "/api/access/grant",
                "{\"discordId\":\"700000000000000007\",\"days\":30}",
                "GRANT",
                "700000000000000007",
                "30"
            },
            {"/api/access/revoke", "{\"discordId\":\"700000000000000007\"}", "REVOKE", "700000000000000007", null},
            {"/api/access/unlink", "{\"discordId\":\"700000000000000007\"}", "UNLINK", "700000000000000007", null},
            {"/api/access/settle", "{\"reference\":\"AB12CD\"}", "SETTLE", "AB12CD", null},
            {
                "/api/people/700000000000000007/playtime",
                "{\"seconds\":3600}",
                "SET_PLAYTIME",
                "700000000000000007",
                "3600"
            },
        };
        for (final String[] ask : asks) {
            final HttpResponse<String> asked = post(ask[0], ask[1]);
            assertEquals(202, asked.statusCode(), ask[0] + ": " + asked.body());
            final JsonObject answer = GSON.fromJson(asked.body(), JsonObject.class);
            final long id = answer.get("id").getAsLong();

            try (var connection = data.dataSource().getConnection();
                    var statement = connection.prepareStatement("select kind, subject, argument,"
                            + " actor_kind, actor_id from access_request where id = ?")) {
                statement.setLong(1, id);
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next(), ask[0] + " wrote no access_request row");
                    assertEquals(ask[2], row.getString("kind"));
                    assertEquals(ask[3], row.getString("subject"));
                    assertEquals(ask[4], row.getString("argument"));
                    assertEquals("PERSON", row.getString("actor_kind"));
                    // The admin's Discord id, which is what the bot re-reads and journals.
                    assertEquals("1", row.getString("actor_id"));
                }
            }

            final JsonObject polled =
                    GSON.fromJson(get("/api/access/requests/" + id).body(), JsonObject.class);
            assertEquals(ask[2], polled.get("kind").getAsString());
            assertEquals("PENDING", polled.get("status").getAsString());
        }
        assertEquals(
                journalBefore,
                count("select count(*) from audit_log"),
                "steward-ui journalled an access change the bot will journal itself");

        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from access_request where subject in" + " ('700000000000000007', 'AB12CD')");
        }
    }

    @Test
    void theBotsAnswerIsRead() throws Exception {
        final long id = GSON.fromJson(
                        post("/api/access/revoke", "{\"discordId\":\"700000000000000008\"}")
                                .body(),
                        JsonObject.class)
                .get("id")
                .getAsLong();
        // Standing in for the bot: claim everything waiting, then answer this one.
        while (data.accessRequests().claim().isPresent()) {
            // drained
        }
        data.accessRequests().finish(id, true, "{\"revoked\":\"2\"}");

        final JsonObject polled =
                GSON.fromJson(get("/api/access/requests/" + id).body(), JsonObject.class);
        assertEquals("DONE", polled.get("status").getAsString());
        assertEquals("2", polled.getAsJsonObject("result").get("revoked").getAsString());

        assertEquals(404, get("/api/access/requests/999999999").statusCode());
        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from access_request where subject = '700000000000000008'");
        }
    }
}
