package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequests;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Tests the CSRF token, health, the service table, and the run and access rows. */
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
        // A form posted from another site carries the cookie but cannot read /api/me.
        final HttpResponse<String> refused = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/api/services/smp/console"))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/api/services/smp/console"))
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
        final JsonObject args = line.getAsJsonObject("line").getAsJsonObject("args");
        assertEquals("list", args.getAsJsonObject("command").get("value").getAsString());
        assertEquals("smp", args.getAsJsonObject("service").get("value").getAsString());
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
        final HttpResponse<String> answer = get(browser(), "/api/health");
        final JsonObject health = GSON.fromJson(answer.body(), JsonObject.class);

        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(health.get("database").getAsBoolean(), "the test database is up");
        assertTrue(health.get("agent").getAsBoolean(), "the stand-in agent is up");
        assertEquals(2, health.size(), "a route open to anyone says nothing beyond the two booleans: " + health);
    }

    /**
     * Checks that a {@code HEAD} against the health route answers like its {@code GET}, which a monitor sends first.
     *
     * The gate looks up roles by exact method, so an unrouted {@code HEAD} reads as undecided.
     */
    @Test
    void headOnHealthIsNotUndecided() throws Exception {
        final HttpResponse<Void> head = browser()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/api/health"))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/api/updates"))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"BACKUP\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, asked.statusCode(), asked.body());
        final JsonObject row = GSON.fromJson(asked.body(), JsonObject.class);
        assertEquals("BACKUP", row.get("kind").getAsString());
        assertEquals("PENDING", row.get("status").getAsString());
        assertEquals("PERSON", row.get("actorKind").getAsString(), row.toString());
        assertEquals("1", row.get("actorId").getAsString(), row.toString());

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
        assertTrue(second.body().contains("Run #" + id + " (down) is still pending"), second.body());
        settleOpenRuns();
    }

    @Test
    void theActiveRunCarriesItsScope() throws Exception {
        settleOpenRuns();
        final JsonObject none = GSON.fromJson(get("/api/updates/active").body(), JsonObject.class);
        assertFalse(none.has("run"), none.toString());

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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/api/updates"))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"DELETE_EVERYTHING\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, refused.statusCode(), refused.body());
    }

    /** A grant of six million years is refused as a slip of the keyboard, and the cap is a decade. */
    @Test
    void anAbsurdGrantIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/access/grant", "{\"discordId\":\"1\",\"days\":2147483647}");
        assertEquals(400, refused.statusCode(), refused.body());

        final HttpResponse<String> decade = post("/api/access/grant", "{\"discordId\":\"1\",\"days\":3650}");
        assertEquals(400, decade.statusCode(), decade.body());
        assertTrue(decade.body().contains("1 to 365 days"), decade.body());
    }

    /**
     * A grant from here is a row in the bot's inbox that the bot carries out, answered 202 with its id.
     *
     * Only the bot sets the role, messages the member and journals, so this side writes no journal line.
     */
    @Test
    void accessChangesAskTheBot() throws Exception {
        final long journalBefore = count("select count(*) from journal_entry");
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
                count("select count(*) from journal_entry"),
                "steward journalled an access change the bot will journal itself");

        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox");
        }
    }

    private void assertAsked(final long id, final String[] ask) throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.prepareStatement("select kind, written = cast(? AS jsonb) AS asked,"
                        + " written AS payload, actor_kind, actor_id from (select i.*, jsonb_strip_nulls(coalesce("
                        + " (select to_jsonb(t) from bot_grant t where t.request = i.id),"
                        + " (select to_jsonb(t) from bot_revoke t where t.request = i.id),"
                        + " (select to_jsonb(t) from bot_unlink t where t.request = i.id),"
                        + " (select to_jsonb(t) from bot_set_playtime t where t.request = i.id),"
                        + " i.payload)) - 'request' AS written from bot_inbox i) AS asked_rows"
                        + " where id = ?")) {
            statement.setString(1, ask[3]);
            statement.setLong(2, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next(), ask[0] + " wrote no row into the bot's inbox");
                assertEquals(ask[2], row.getString("kind"));
                assertTrue(row.getBoolean("asked"), ask[0] + " asked for " + row.getString("payload"));
                assertEquals("PERSON", row.getString("actor_kind"));
                assertEquals("1", row.getString("actor_id"));
            }
        }
    }

    /** A booking by hand is booked here in one answer. The bot is only told, and the admin is the actor. */
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
        // Stands in for the bot, as the owner: claims everything waiting and answers this one.
        eu.nordtal.season.database.inbox.Inbox.over(
                        WebFixture.postgres.dataSource(), eu.nordtal.season.database.inbox.BotRequest.TABLE)
                .drain(request -> request.id() == id
                        ? eu.nordtal.season.database.inbox.Outcome.done(
                                new eu.nordtal.season.database.inbox.BotRequest.Revoke.Answer(2))
                        : eu.nordtal.season.database.inbox.Outcome.failed(null));

        final JsonObject polled =
                GSON.fromJson(get("/api/access/requests/" + id).body(), JsonObject.class);
        assertEquals("DONE", polled.get("status").getAsString());
        assertEquals(2, polled.getAsJsonObject("result").get("revoked").getAsInt());

        assertEquals(404, get("/api/access/requests/999999999").statusCode());
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox");
        }
    }
}
