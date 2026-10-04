package eu.nordtal.s2.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.DatabaseMessages;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MilestoneContext;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The game actions and announcements, and the requests they become. */
class RosterApiTest extends WebTestSupport {

    @Test
    void aGameActionIsARowWithTheAskerOnIt() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO smp_milestone (key, state, unlocked) VALUES ('r-open', 'ACTIVE', NULL)");
        }
        final HttpResponse<String> asked;
        try {
            asked = post("/api/smp/milestone", "{\"key\": \"r-open\"}");
        } finally {
            try (var connection = WebFixture.postgres.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM smp_milestone WHERE key = 'r-open'");
            }
        }

        assertEquals(202, asked.statusCode(), asked.body());
        final String id =
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString();

        // Reading the row back through the endpoint the browser polls proves the round trip.
        final JsonObject outcome = GSON.fromJson(get("/api/commands/" + id).body(), JsonObject.class);
        assertEquals("PENDING", outcome.get("status").getAsString());

        // A web request is a person's, by Discord id, and carries its typed parameters and nothing else.
        final java.util.Map<String, String> row = row(id);
        assertEquals("UNLOCK_MILESTONE", row.get("kind"));
        assertEquals("PERSON", row.get("actor_kind"));
        assertEquals("1", row.get("actor_id"));
        assertEquals(GSON.fromJson("{\"key\": \"r-open\"}", JsonObject.class), payload(row));
    }

    /** Returns the request a web action wrote, named as the browser was told. */
    private static java.util.Map<String, String> row(final String id) throws Exception {
        final String[] name = id.split(":", -1);
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        try (var connection = java.sql.DriverManager.getConnection(
                        postgres.jdbcUrl(), postgres.username(), postgres.password());
                var statement =
                        connection.prepareStatement("SELECT kind, actor_kind, actor_id, payload::text AS payload FROM "
                                + name[0] + "_inbox WHERE id = ?")) {
            statement.setLong(1, Long.parseLong(name[1]));
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the row is not there: " + id);
                row.put("kind", rows.getString("kind"));
                row.put("actor_kind", rows.getString("actor_kind"));
                row.put("actor_id", rows.getString("actor_id"));
                row.put("payload", rows.getString("payload"));
            }
        }
        return row;
    }

    private static JsonObject payload(final java.util.Map<String, String> row) {
        return GSON.fromJson(row.get("payload"), JsonObject.class);
    }

    /**
     * The service pages ask for what they act on, never by command name.
     *
     * The whole track is read; only the active milestone and its open objectives can be acted on.
     */
    @Test
    void gameActionsAreRowsWithoutACommandName() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO smp_milestone (key, state, unlocked) VALUES
                        ('t-open', 'ACTIVE', NULL), ('t-later', 'LOCKED', NULL), ('t-done', 'UNLOCKED', now());
                    INSERT INTO smp_objective (milestone_key, key, type, amount, target, completed) VALUES
                        ('t-open', 't-iron', 'HAND_IN', 64, 128, NULL),
                        ('t-open', 't-coal', 'STATISTIC', 10, 10, now()),
                        ('t-later', 't-gold', 'HAND_IN', 0, 64, NULL);
                    """);
        }
        try {
            final JsonObject track = GSON.fromJson(get("/api/smp/track").body(), JsonObject.class);
            final JsonArray milestones = track.getAsJsonArray("milestones");
            final java.util.Map<String, JsonObject> byKey = new java.util.HashMap<>();
            milestones.forEach(
                    each -> byKey.put(each.getAsJsonObject().get("key").getAsString(), each.getAsJsonObject()));
            assertEquals("ACTIVE", byKey.get("t-open").get("state").getAsString(), track.toString());
            assertEquals("LOCKED", byKey.get("t-later").get("state").getAsString(), track.toString());
            assertEquals("UNLOCKED", byKey.get("t-done").get("state").getAsString(), track.toString());
            assertEquals(2, byKey.get("t-open").getAsJsonArray("objectives").size(), track.toString());
            assertEquals(1, byKey.get("t-later").getAsJsonArray("objectives").size(), track.toString());
            assertEquals(0, byKey.get("t-done").getAsJsonArray("objectives").size(), track.toString());

            assertEquals(400, post("/api/smp/objective", "{\"key\":\"t-coal\"}").statusCode());
            assertEquals(400, post("/api/smp/objective", "{\"key\":\"t-gold\"}").statusCode());
            assertEquals(400, post("/api/smp/objective", "{}").statusCode());
            assertEquals(
                    400, post("/api/smp/milestone", "{\"key\":\"t-later\"}").statusCode());
            assertEquals(400, post("/api/smp/milestone", "{\"key\":\"t-done\"}").statusCode());

            assertRow(
                    post("/api/smp/objective", "{\"key\":\"t-iron\"}"),
                    "SMP",
                    "COMPLETE_OBJECTIVE",
                    "{\"key\":\"t-iron\"}");
            assertRow(
                    post("/api/smp/milestone", "{\"key\":\"t-open\"}"),
                    "SMP",
                    "UNLOCK_MILESTONE",
                    "{\"key\":\"t-open\"}");
        } finally {
            try (var connection = WebFixture.postgres.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM smp_milestone WHERE key LIKE 't-%'");
            }
        }
    }

    /** The start carries whether the admin confirmed it, which the server needs below the recommended minimum. */
    @Test
    void theHungerGamesStartIsARowSayingWhetherItWasConfirmed() throws Exception {
        assertEquals("{}", get("/api/hunger-games/round").body());
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO hg_game (state) VALUES ('REGISTRATION')");
        }
        try {
            final JsonObject round =
                    GSON.fromJson(get("/api/hunger-games/round").body(), JsonObject.class);
            assertEquals("REGISTRATION", round.get("state").getAsString(), round.toString());
            assertEquals(0, round.get("registered").getAsLong(), round.toString());
            assertRow(post("/api/hunger-games/start", "{}"), "HUNGER_GAMES", "START_GAME", "{\"confirmed\":false}");
            assertRow(
                    post("/api/hunger-games/start", "{\"confirm\":true}"),
                    "HUNGER_GAMES",
                    "START_GAME",
                    "{\"confirmed\":true}");
        } finally {
            try (var connection = WebFixture.postgres.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM hg_game");
            }
        }
    }

    /** One form, one request with every language, and nothing written while any language is missing its text. */
    @Test
    void anAnnouncementIsOneRequestWithEveryLanguage() throws Exception {
        final long before = count("SELECT count(*) FROM bot_inbox WHERE kind = 'ANNOUNCE'");
        assertEquals(400, post("/api/announcements", "{\"texts\":{}}").statusCode());
        assertEquals(
                400,
                post("/api/announcements", "{\"texts\":{\"en\":\"Hello\",\"de\":\"  \"}}")
                        .statusCode());
        assertEquals(
                400,
                post("/api/announcements", "{\"texts\":{\"EN x\":\"Hello\"}}").statusCode());
        assertEquals(
                400,
                post("/api/announcements", "{\"texts\":{\"en\":\"" + "x".repeat(Announcements.MAX_LENGTH + 1) + "\"}}")
                        .statusCode());
        assertEquals(
                before,
                count("SELECT count(*) FROM bot_inbox WHERE kind = 'ANNOUNCE'"),
                "a refused announcement wrote a row");

        final HttpResponse<String> sent = post(
                "/api/announcements",
                "{\"texts\":{\"en\":\"The end opens tonight.\",\"de\":\"The second language, tonight.\"}}");
        assertEquals(202, sent.statusCode(), sent.body());
        final JsonObject ids = GSON.fromJson(sent.body(), JsonObject.class).getAsJsonObject("ids");
        assertEquals(2, ids.size(), sent.body());
        assertEquals(before + 1, count("SELECT count(*) FROM bot_inbox WHERE kind = 'ANNOUNCE'"));
        for (final String tag : List.of("en", "de")) {
            final JsonObject line = GSON.fromJson(
                    get("/api/commands/" + ids.get(tag).getAsString()).body(), JsonObject.class);
            assertEquals("PENDING", line.get("status").getAsString(), line.toString());
        }

        final JsonArray recent = GSON.fromJson(get("/api/announcements").body(), JsonObject.class)
                .getAsJsonArray("recent");
        final java.util.Map<String, JsonObject> byLanguage = new java.util.HashMap<>();
        // Newest first, so the first line of a language is this announcement's.
        recent.forEach(line ->
                byLanguage.putIfAbsent(line.getAsJsonObject().get("language").getAsString(), line.getAsJsonObject()));
        assertEquals(java.util.Set.of("en", "de"), byLanguage.keySet(), recent.toString());
        assertEquals("The end opens tonight.", byLanguage.get("en").get("text").getAsString());
        assertEquals("PENDING", byLanguage.get("en").get("status").getAsString());
        assertEquals("PERSON", byLanguage.get("en").get("actorKind").getAsString());
    }

    @Test
    void theListShowsEachLanguageAsTheBotPostsIt() throws Exception {
        final MessageRef border =
                DatabaseMessages.MESSAGES.announcement().milestoneSection().border(new MilestoneContext("Frontier"));
        // As the SMP sends a milestone: one message per language, for the bot to render.
        Inbox.over(WebFixture.postgres.dataSource(), BotRequest.TABLE)
                .submit(
                        new BotRequest.Announce(Map.of("en", border, "de", border)),
                        Actor.STEWARD,
                        Schedule.within(Duration.ofHours(1)));

        final java.util.Map<String, String> byLanguage = new java.util.HashMap<>();
        GSON.fromJson(get("/api/announcements").body(), JsonObject.class)
                .getAsJsonArray("recent")
                .forEach(line -> byLanguage.putIfAbsent(
                        line.getAsJsonObject().get("language").getAsString(),
                        line.getAsJsonObject().get("text").getAsString()));
        final Messages bundle = Messages.load(RosterApiTest.class.getClassLoader(), "messages/database", Locale.GERMAN);
        assertEquals("Frontier is complete - the border grows.", byLanguage.get("en"));
        assertEquals(bundle.format(Locale.GERMAN, border), byLanguage.get("de"), "the German line is the bundle's");
        assertNotEquals(byLanguage.get("en"), byLanguage.get("de"), "each language is rendered in its own words");
    }

    private static void assertRow(
            final HttpResponse<String> asked, final String target, final String kind, final String payload)
            throws Exception {
        assertEquals(202, asked.statusCode(), asked.body());
        final JsonObject answer = GSON.fromJson(asked.body(), JsonObject.class);
        assertFalse(answer.has("name"), "the browser was told a command name: " + answer);
        final String id = answer.get("id").getAsString();
        assertTrue(id.startsWith(target.toLowerCase(java.util.Locale.ROOT) + ":"), id);
        final java.util.Map<String, String> row = row(id);
        assertEquals(kind, row.get("kind"));
        assertEquals(GSON.fromJson(payload, JsonObject.class), payload(row));
    }

    @Test
    void anArgumentThatIsNotASingleValueIsRefused() throws Exception {
        // getAsString() on a JsonArray or JsonObject throws, which Javalin turns into a 500, not a 400.
        final long before = commandRequestCount();

        final HttpResponse<String> asList = post("/api/smp/milestone", "{\"key\": [\"a\", \"b\"]}");
        assertEquals(400, asList.statusCode(), asList.body());

        final HttpResponse<String> asObject = post("/api/smp/milestone", "{\"key\": {\"a\": 1}}");
        assertEquals(400, asObject.statusCode(), asObject.body());

        // Neither of them is a row: a 400 that had already written the request would be the same bug.
        assertEquals(before, commandRequestCount(), "a refused command was written into the inbox anyway");
    }

    private static long commandRequestCount() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                        postgres.jdbcUrl(), postgres.username(), postgres.password());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM smp_inbox")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
