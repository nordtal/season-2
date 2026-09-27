package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The plugin catalogue, linking an account, and the commands and game actions rows they become.
 */
class RosterApiTest extends StewardUiTestSupport {

    @Test
    void theCatalogueIsFiltered() throws Exception {
        final JsonArray offered = GSON.fromJson(get("/api/commands").body(), JsonArray.class);

        // Alphabetical, the order the catalogue answers in; the twin list lives in :commands' WebSurfaceTest.
        assertEquals(
                List.of(
                        "/access settle",
                        "/access unlink",
                        "/announce",
                        "/hg start",
                        "/phase launch",
                        "/phase set",
                        "/phase show",
                        "/phase smp-start",
                        "/smp milestone unlock",
                        "/smp objective complete"),
                offered.asList().stream()
                        .map(command -> command.getAsJsonObject().get("name").getAsString())
                        .toList());

        // `REFERENCE` and `ACCOUNT` must not draw as text fields, since they switch which endpoint is fetched.
        final JsonObject settle = offered.asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(command -> "/access settle".equals(command.get("name").getAsString()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "REFERENCE",
                settle.getAsJsonArray("arguments")
                        .get(0)
                        .getAsJsonObject()
                        .get("kind")
                        .getAsString(),
                settle.toString());

        final JsonObject unlink = offered.asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(command -> "/access unlink".equals(command.get("name").getAsString()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "ACCOUNT",
                unlink.getAsJsonArray("arguments")
                        .get(0)
                        .getAsJsonObject()
                        .get("kind")
                        .getAsString(),
                unlink.toString());
    }

    @Test
    void settlingTravelsAsARow() throws Exception {
        final HttpResponse<String> asked =
                post("/api/commands", "{\"name\": \"/access settle\", \"arguments\": {\"reference\": \"AB12CD\"}}");
        assertEquals(202, asked.statusCode(), asked.body());
        final long id = Long.parseLong(
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString());

        try (var connection = data.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT target, command, arguments, source FROM command_request WHERE id = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the row was not written");
                assertEquals("BOT", rows.getString("target"));
                assertEquals("access settle", rows.getString("command"));
                assertEquals("AB12CD", rows.getString("arguments"));
                assertEquals("WEB", rows.getString("source"));
            }
        }
    }

    @Test
    void unlinkingTakesAnAccount() throws Exception {
        final HttpResponse<String> asked = post(
                "/api/commands", "{\"name\": \"/access unlink\", \"arguments\": {\"member\": \"100000000000000009\"}}");
        assertEquals(202, asked.statusCode(), asked.body());

        // A Minecraft name is what the row cannot carry: the bot reads `member` as a snowflake.
        final HttpResponse<String> refused =
                post("/api/commands", "{\"name\": \"/access unlink\", \"arguments\": {\"member\": \"Notch\"}}");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("pick the person from the list"), refused.body());
    }

    @Test
    void aCommandIsARowWithANameOnIt() throws Exception {
        final HttpResponse<String> asked =
                post("/api/commands", "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": \"aufbruch\"}}");

        assertEquals(202, asked.statusCode(), asked.body());
        final long id = Long.parseLong(
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString());

        // Reading the row back through the endpoint the browser polls is what proves the round trip.
        final JsonObject outcome = GSON.fromJson(get("/api/commands/" + id).body(), JsonObject.class);
        assertEquals("PENDING", outcome.get("status").getAsString());

        // source = WEB is the whole reason V18 exists: a CONSOLE row must never carry a Discord id.
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
                assertEquals("aufbruch", rows.getString("arguments"));
            }
        }
    }

    /**
     * The service pages ask for what they act on, and never by command name.
     *
     * Only what can be acted on is offered and accepted: the open objectives of the
     * active milestone and that milestone itself, because unlocking one further down the track by
     * hand skips the ones before it.
     */
    @Test
    void gameActionsAreRowsWithoutACommandName() throws Exception {
        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO smp_milestone (key, state) VALUES
                        ('t-open', 'ACTIVE'), ('t-later', 'LOCKED'), ('t-done', 'UNLOCKED');
                    INSERT INTO smp_objective (milestone_key, key, type, amount, target, completed) VALUES
                        ('t-open', 't-iron', 'HAND_IN', 64, 128, NULL),
                        ('t-open', 't-coal', 'STATISTIC', 10, 10, now()),
                        ('t-later', 't-gold', 'HAND_IN', 0, 64, NULL);
                    """);
        }
        try {
            final JsonObject track = GSON.fromJson(get("/api/smp/track").body(), JsonObject.class);
            final JsonArray active = track.getAsJsonArray("active");
            assertEquals(1, active.size(), track.toString());
            final JsonObject open = active.get(0).getAsJsonObject();
            assertEquals("t-open", open.get("key").getAsString());
            assertEquals(2, open.getAsJsonArray("objectives").size(), track.toString());

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

    /**
     * One form, one row per language.
     *
     * Nothing is written when any language is missing its text: an announcement in one of two
     * languages is the mistake the form is for.
     */
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
    void anUndeclaredCommandIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/commands", "{\"name\": \"/smp aura\"}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("/smp aura"), refused.body());
    }

    @Test
    void aMissingArgumentIsRefused() throws Exception {
        final HttpResponse<String> refused =
                post("/api/commands", "{\"name\": \"/smp objective complete\", \"arguments\": {}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("key"), refused.body());
    }

    @Test
    void anArgumentThatIsNotASingleValueIsRefused() throws Exception {
        // getAsString() on a JsonArray or JsonObject throws, which Javalin turns into a 500, not a 400.
        final long before = commandRequestCount();

        final HttpResponse<String> asList = post(
                "/api/commands", "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": [\"a\", \"b\"]}}");
        assertEquals(400, asList.statusCode(), asList.body());
        assertTrue(asList.body().contains("key") && asList.body().contains("list"), asList.body());

        final HttpResponse<String> asObject =
                post("/api/commands", "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": {\"a\": 1}}}");
        assertEquals(400, asObject.statusCode(), asObject.body());
        assertTrue(asObject.body().contains("key") && asObject.body().contains("structure"), asObject.body());

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
