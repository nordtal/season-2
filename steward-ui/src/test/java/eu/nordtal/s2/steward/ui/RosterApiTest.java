package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The game actions and announcements, and the command rows they become. */
class RosterApiTest extends StewardUiTestSupport {

    @Test
    void aGameActionIsARowWithTheAskerOnIt() throws Exception {
        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO smp_milestone (key, state, unlocked) VALUES ('r-open', 'ACTIVE', NULL)");
        }
        final HttpResponse<String> asked;
        try {
            asked = post("/api/smp/milestone", "{\"key\": \"r-open\"}");
        } finally {
            try (var connection = data.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM smp_milestone WHERE key = 'r-open'");
            }
        }

        assertEquals(202, asked.statusCode(), asked.body());
        final long id = Long.parseLong(
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString());

        // Reading the row back through the endpoint the browser polls proves the round trip.
        final JsonObject outcome = GSON.fromJson(get("/api/commands/" + id).body(), JsonObject.class);
        assertEquals("PENDING", outcome.get("status").getAsString());

        // A WEB row carries a Discord id; a CONSOLE row never does.
        try (var connection = java.sql.DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement =
                        connection.prepareStatement("SELECT source, discord_id, requested_by, command, arguments"
                                + " FROM command_request WHERE id = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the row is not there");
                assertEquals("WEB", rows.getString("source"));
                assertEquals("1", rows.getString("discord_id"));
                assertTrue(rows.getString("requested_by").contains("Ally"), rows.getString("requested_by"));
                assertEquals("smp milestone unlock", rows.getString("command"));
                assertEquals("r-open", rows.getString("arguments"));
            }
        }
    }

    /**
     * The service pages ask for what they act on, never by command name.
     *
     * The whole track is read; only the active milestone and its open objectives can be acted on.
     */
    @Test
    void gameActionsAreRowsWithoutACommandName() throws Exception {
        try (var connection = data.dataSource().getConnection();
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

            assertRow(post("/api/smp/objective", "{\"key\":\"t-iron\"}"), "SMP", "smp objective complete", "t-iron");
            assertRow(post("/api/smp/milestone", "{\"key\":\"t-open\"}"), "SMP", "smp milestone unlock", "t-open");
            assertEquals("{}", get("/api/hunger-games/round").body());
            try (var connection = data.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("INSERT INTO hg_game (state) VALUES ('REGISTRATION')");
            }
            final JsonObject round =
                    GSON.fromJson(get("/api/hunger-games/round").body(), JsonObject.class);
            assertEquals("REGISTRATION", round.get("state").getAsString(), round.toString());
            assertEquals(0, round.get("registered").getAsLong(), round.toString());
            assertRow(post("/api/hunger-games/start", "{}"), "HUNGER_GAMES", "hg start", "");
            assertRow(post("/api/hunger-games/start", "{\"confirm\":true}"), "HUNGER_GAMES", "hg start", "confirm");
        } finally {
            try (var connection = data.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM smp_milestone WHERE key LIKE 't-%'");
                statement.execute("DELETE FROM hg_game");
            }
        }
    }

    /** One form, one row per language, and nothing written while any language is missing its text. */
    @Test
    void anAnnouncementIsOneRowPerLanguage() throws Exception {
        final long before = count("SELECT count(*) FROM command_request WHERE command = 'announce'");
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
                count("SELECT count(*) FROM command_request WHERE command = 'announce'"),
                "a refused announcement wrote a row");

        final HttpResponse<String> sent = post(
                "/api/announcements",
                "{\"texts\":{\"en\":\"The end opens tonight.\",\"de\":\"The second language, tonight.\"}}");
        assertEquals(202, sent.statusCode(), sent.body());
        final JsonObject ids = GSON.fromJson(sent.body(), JsonObject.class).getAsJsonObject("ids");
        assertEquals(2, ids.size(), sent.body());
        for (final String tag : List.of("en", "de")) {
            try (var connection = data.dataSource().getConnection();
                    var statement = connection.prepareStatement(
                            "SELECT target, command, arguments, source FROM command_request WHERE id = ?")) {
                statement.setLong(1, Long.parseLong(ids.get(tag).getAsString()));
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next(), "no row for " + tag);
                    assertEquals("BOT", rows.getString("target"));
                    assertEquals("announce", rows.getString("command"));
                    assertTrue(rows.getString("arguments").startsWith(tag + " "), rows.getString("arguments"));
                    assertEquals("WEB", rows.getString("source"));
                }
            }
        }

        final JsonArray recent = GSON.fromJson(get("/api/announcements").body(), JsonObject.class)
                .getAsJsonArray("recent");
        final JsonObject newest = recent.get(0).getAsJsonObject();
        assertEquals("de", newest.get("language").getAsString(), recent.toString());
        assertEquals("The second language, tonight.", newest.get("text").getAsString());
        assertEquals("PENDING", newest.get("status").getAsString());
        assertEquals("en", recent.get(1).getAsJsonObject().get("language").getAsString());
    }

    private static void assertRow(
            final HttpResponse<String> asked, final String target, final String command, final String arguments)
            throws Exception {
        assertEquals(202, asked.statusCode(), asked.body());
        final JsonObject answer = GSON.fromJson(asked.body(), JsonObject.class);
        assertFalse(answer.has("name"), "the browser was told a command name: " + answer);
        try (var connection = data.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT target, command, arguments, source FROM command_request WHERE id = ?")) {
            statement.setLong(1, Long.parseLong(answer.get("id").getAsString()));
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the row was not written");
                assertEquals(target, rows.getString("target"));
                assertEquals(command, rows.getString("command"));
                assertEquals(arguments, rows.getString("arguments"));
                assertEquals("WEB", rows.getString("source"));
            }
        }
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
        assertEquals(before, commandRequestCount(), "a refused command was written into command_request anyway");
    }

    private static long commandRequestCount() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM command_request")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
