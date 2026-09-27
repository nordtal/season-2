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

/**
 * The proxy in front of steward-worker: the token it adds, health, and the command run rows.
 */
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
     * Javalin answers a HEAD against a registered GET by discarding the body at the wire layer.
     *
     * {@code guard}'s {@code beforeMatched} reads {@code ctx.routeRoles()}, and
     * that lookup is keyed to the exact HTTP method. With no route ever registered for
     * {@code HEAD /api/health}, it saw zero decided roles and {@code gateOf} refused it as an
     * undecided route: a 500 that named a fault this service does not have. A real monitor tries
     * HEAD before GET because it is cheaper, so this is exactly the request an outside watcher
     * would send first - and the health route is the one place it has to come back cheap.
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
        // Who asked is written down, since with three admins a name is the difference from "strange".
        assertTrue(row.get("requestedBy").getAsString().contains("Ally"), row.toString());
        // The same actor fields the unified actions feed carries, read out of the same string.
        assertEquals("Ally (1)", row.get("actorLabel").getAsString(), row.toString());
        assertEquals("", row.get("actorDiscordId").getAsString(), row.toString());
        assertFalse(row.get("system").getAsBoolean(), row.toString());

        // And it is in the list the interface draws its runs from.
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

    /**
     * A grant of six million years is a slip of the keyboard, and it has to read like one.
     *
     * The only check was {@code days > 0}, so {@code 2147483647} went to PostgreSQL, where
     * {@code make_interval(hours => :days * 24)} overflows an integer and the driver reports it -
     * a 500 blaming this program for a number the operator typed. The ceiling is a decade, which
     * is nine seasons more than anybody will ever buy.
     */
    @Test
    void anAbsurdGrantIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/access/grant", "{\"discordId\":\"1\",\"days\":2147483647}");
        assertEquals(400, refused.statusCode(), refused.body());

        // 3650 was the old ceiling: a decade of free access, one keystroke away from 365.
        final HttpResponse<String> decade = post("/api/access/grant", "{\"discordId\":\"1\",\"days\":3650}");
        assertEquals(400, decade.statusCode(), decade.body());
        assertTrue(decade.body().contains("between 1 and 365 days"), decade.body());
    }

    /**
     * A grant from here is a row the bot carries out, not a write to the access tables.
     *
     * Writing the tables directly skipped the role, the direct message and the admin note - the
     * three things only the bot can do - and nobody granted access from a browser was ever told.
     * So every one of the five writes has to leave an {@code access_request} row signed by the
     * admin, answer 202 with its id, and write no journal line of its own: the bot journals what it
     * carries out.
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
                            + " source, requested_by from access_request where id = ?")) {
                statement.setLong(1, id);
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next(), ask[0] + " wrote no access_request row");
                    assertEquals(ask[2], row.getString("kind"));
                    assertEquals(ask[3], row.getString("subject"));
                    assertEquals(ask[4], row.getString("argument"));
                    assertEquals("STEWARD", row.getString("source"));
                    // The admin's Discord id, which is what the bot re-reads and journals.
                    assertEquals("1", row.getString("requested_by"));
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
