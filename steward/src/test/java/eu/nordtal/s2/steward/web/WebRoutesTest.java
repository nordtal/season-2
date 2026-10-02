package eu.nordtal.s2.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.payment.PaymentRequests;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The interface's own doors: the CSRF token, health, the service table, and the run and access rows. */
class WebRoutesTest extends WebTestSupport {

    @Test
    void theServiceTableIsReadFromTheDaemon() throws Exception {
        final HttpResponse<String> response = get("/api/services");

        assertEquals(200, response.statusCode(), response.body());
        final JsonArray services =
                GSON.fromJson(response.body(), JsonObject.class).getAsJsonArray("services");
        assertEquals("smp", services.get(0).getAsJsonObject().get("service").getAsString());
        assertTrue(services.get(0).getAsJsonObject().get("hasConsole").getAsBoolean());
    }

    @Test
    void theCookieAloneIsNotEnough() throws Exception {
        // A form posted from another site carries the cookie. It cannot read /api/me.
        final HttpResponse<String> refused = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + "/api/services/smp/console"))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + "/api/services/smp/console"))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"list\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, accepted.statusCode(), accepted.body());
        assertTrue(
                daemon.execs.stream().anyMatch(exec -> exec.contains("\"mc\"") && exec.contains("\"list\"")),
                "the line never reached the container's console: " + daemon.execs);
        final JsonObject line = newestJournalRow("CONSOLE");
        assertEquals("1", line.getAsJsonObject("actor").get("person").getAsString(), "the admin, as a column");
        assertEquals("list", line.getAsJsonObject("facts").get("command").getAsString());
        assertEquals("smp", line.getAsJsonObject("facts").get("service").getAsString());
    }

    @Test
    void aDaemonThatDoesNotAnswerIsASentenceNamingDocker() throws Exception {
        daemon.broken.set(true);
        try {
            final HttpResponse<String> response = get("/api/services");

            assertEquals(502, response.statusCode(), response.body());
            final JsonObject error = GSON.fromJson(response.body(), JsonObject.class);
            assertEquals(
                    "docker",
                    error.get("where").getAsString(),
                    "an empty table would look like a stack with nothing running");
            assertFalse(error.get("error").getAsString().isBlank());
        } finally {
            daemon.broken.set(false);
        }
    }

    @Test
    void healthIsOpen() throws Exception {
        final JsonObject health = GSON.fromJson(get(browser(), "/api/health").body(), JsonObject.class);

        assertEquals("ok", health.get("status").getAsString());
        assertTrue(health.get("agent").getAsBoolean(), "the stand-in agent is up");
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
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + "/api/health"))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.discarding());

        assertEquals(200, head.statusCode(), "HEAD on an ANYONE route must not be refused as undecided");
    }

    @Test
    void askingIsARow() throws Exception {
        settleOpenRuns();
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final HttpResponse<String> asked = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + "/api/updates"))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + "/api/updates"))
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
     * A grant from here is a row in the bot's inbox, which the bot carries out, answered 202 with its id.
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
                "{\"person\":\"700000000000000007\",\"days\":30}"
            },
            {
                "/api/access/revoke",
                "{\"discordId\":\"700000000000000007\"}",
                "REVOKE",
                "{\"person\":\"700000000000000007\"}"
            },
            {
                "/api/access/unlink",
                "{\"discordId\":\"700000000000000007\"}",
                "UNLINK",
                "{\"person\":\"700000000000000007\"}"
            },
            {
                "/api/people/700000000000000007/playtime",
                "{\"seconds\":3600}",
                "SET_PLAYTIME",
                "{\"person\":\"700000000000000007\",\"seconds\":3600}"
            },
        };
        for (final String[] ask : asks) {
            final HttpResponse<String> asked = post(ask[0], ask[1]);
            assertEquals(202, asked.statusCode(), ask[0] + ": " + asked.body());
            final JsonObject answer = GSON.fromJson(asked.body(), JsonObject.class);
            final long id = answer.get("id").getAsLong();

            assertAsked(id, ask);

            final JsonObject polled =
                    GSON.fromJson(get("/api/access/requests/" + id).body(), JsonObject.class);
            assertEquals(ask[2], polled.get("kind").getAsString());
            assertEquals("PENDING", polled.get("status").getAsString());
        }
        assertEquals(
                journalBefore,
                count("select count(*) from audit_log"),
                "steward journalled an access change the bot will journal itself");

        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox");
        }
    }

    /** Holds the row one access change wrote: its kind, its payload and the admin who asked. */
    private void assertAsked(final long id, final String[] ask) throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.prepareStatement("select kind, payload = cast(? AS jsonb) AS asked,"
                        + " payload, actor_kind, actor_id from bot_inbox where id = ?")) {
            statement.setString(1, ask[3]);
            statement.setLong(2, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next(), ask[0] + " wrote no row into the bot's inbox");
                assertEquals(ask[2], row.getString("kind"));
                assertTrue(row.getBoolean("asked"), ask[0] + " asked for " + row.getString("payload"));
                assertEquals("PERSON", row.getString("actor_kind"));
                // The admin's Discord id, which is what the bot re-reads and journals.
                assertEquals("1", row.getString("actor_id"));
            }
        }
    }

    /** A booking by hand is booked here, in one answer, and the bot is only told; the admin is the actor. */
    @Test
    void aBookingByHandIsBookedHereAndTheBotIsOnlyTold() throws Exception {
        final PaymentRequest request = new PaymentRequests(WebFixture.postgres.dataSource())
                .open(DiscordId.of("700000000000000009"), 30, 300, 0, 24);

        final JsonObject unknown = GSON.fromJson(
                post("/api/access/settle", "{\"reference\":\"NT-ZZZZZZ\"}").body(), JsonObject.class);
        final HttpResponse<String> asked =
                post("/api/access/settle", "{\"reference\":\"" + request.reference() + "\"}");
        final JsonObject booked = GSON.fromJson(asked.body(), JsonObject.class);
        final JsonObject again = GSON.fromJson(
                post("/api/access/settle", "{\"reference\":\"" + request.reference() + "\"}")
                        .body(),
                JsonObject.class);

        assertEquals("UNKNOWN", unknown.get("outcome").getAsString());
        assertEquals(200, asked.statusCode(), asked.body());
        assertEquals("BOOKED", booked.get("outcome").getAsString());
        assertEquals(30, booked.get("days").getAsInt());
        assertEquals("NOT_OPEN", again.get("outcome").getAsString());
        assertEquals("PAID", again.get("was").getAsString());
        assertEquals(
                1,
                count("select count(*) from bot_inbox where kind = 'PAYMENT_BOOKED' and actor_kind = 'PERSON'"
                        + " and actor_id = '1'"));
        assertEquals(1, count("select count(*) from access_grant where source = 'PURCHASE'"));

        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox");
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
        // Standing in for the bot, as the owner: claim everything waiting, and answer this one.
        eu.nordtal.s2.database.inbox.Inbox.over(
                        WebFixture.postgres.dataSource(), eu.nordtal.s2.database.inbox.BotRequest.TABLE)
                .drain(request -> eu.nordtal.s2.database.inbox.Outcome.done(
                        request.id() == id ? java.util.Map.of("revoked", "2") : null));

        final JsonObject polled =
                GSON.fromJson(get("/api/access/requests/" + id).body(), JsonObject.class);
        assertEquals("DONE", polled.get("status").getAsString());
        assertEquals("2", polled.getAsJsonObject("result").get("revoked").getAsString());

        assertEquals(404, get("/api/access/requests/999999999").statusCode());
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox");
        }
    }
}
