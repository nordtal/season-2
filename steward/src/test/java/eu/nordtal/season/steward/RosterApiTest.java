package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.RoundSeed;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.value.Money;
import eu.nordtal.season.steward.game.Announcements;
import eu.nordtal.season.stewardagent.PluginJars;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests the game actions and announcements, and the requests they become. */
class RosterApiTest extends WebTestSupport {

    @Test
    void aPersonsPaymentsAreTheirsAloneNewestFirst() throws Exception {
        final PaymentRequests payments = new PaymentRequests(WebFixture.postgres.dataSource());
        final String ally = "700000000000000001";
        final PaymentRequest older = payments.open(DiscordId.of(ally), 30, 500, 0, 24);
        payments.closeAndRequestCancel(older.id(), PaymentRequestStatus.SUPERSEDED, Actor.STEWARD);
        final PaymentRequest newer = payments.open(DiscordId.of(ally), 90, 1300, 200, 24);
        final PaymentRequest bobs = payments.open(DiscordId.of("700000000000000002"), 30, 500, 0, 24);
        try {
            final HttpResponse<String> answer = get("/api/people/" + ally + "/payments");

            assertEquals(200, answer.statusCode(), answer.body());
            final List<String> references = GSON.fromJson(answer.body(), JsonArray.class).asList().stream()
                    .map(payment -> payment.getAsJsonObject().get("reference").getAsString())
                    .toList();
            assertEquals(List.of(newer.reference(), older.reference()), references, "not " + bobs.reference());
        } finally {
            sql("DELETE FROM bank_inbox WHERE id IN (SELECT request FROM bank_cancel_tab"
                    + " JOIN payment_request ON payment_request.id = bank_cancel_tab.payment"
                    + " WHERE discord_id IN ('700000000000000001', '700000000000000002'))");
            sql("DELETE FROM payment_request WHERE discord_id IN ('700000000000000001', '700000000000000002')");
        }
    }

    @Test
    void theJournalIsFilteredByAPaymentReferenceAndByTheLeastDays() throws Exception {
        final AuditDirectory journal = AuditDirectory.using(WebFixture.postgres.dataSource());
        final PaymentRequests payments = new PaymentRequests(WebFixture.postgres.dataSource());
        final DiscordId payer = DiscordId.of("700000000000000003");
        // A person has one open request, so the second payment belongs to someone else.
        final PaymentRequest route = payments.open(payer, 30, 500, 0, 24);
        final PaymentRequest other = payments.open(DiscordId.of("700000000000000004"), 30, 500, 0, 24);
        for (final PaymentRequest settled : List.of(route, other)) {
            sql("UPDATE payment_request SET status = 'PAID', settled = now(), matched_by = 'MANUAL' WHERE id = '"
                    + settled.id() + "'");
            journal.record(AuditLine.about(
                    JournalAction.SETTLE,
                    Actor.STEWARD,
                    payer,
                    new JournalDetail.Settled(
                            settled.id(), settled.reference(), PaymentMatch.MANUAL, 30, 30, null, Money.euroCents(0))));
        }
        final String reference = route.reference();
        try {
            assertEquals(List.of(reference), references("/api/journal?reference=" + reference));
            assertEquals(List.of(reference), references("/api/journal?reference=" + reference + "&minDays=30"));
            assertEquals(List.of(), references("/api/journal?reference=" + reference + "&minDays=31"));
        } finally {
            sql("DELETE FROM journal_entry WHERE subject = '" + payer + "'");
            sql("DELETE FROM payment_request WHERE discord_id IN ('700000000000000003', '700000000000000004')");
        }
    }

    private static List<String> references(final String query) throws Exception {
        final HttpResponse<String> answer = get(query);
        assertEquals(200, answer.statusCode(), answer.body());
        return GSON.fromJson(answer.body(), JsonArray.class).asList().stream()
                .map(entry -> entry.getAsJsonObject()
                        .getAsJsonObject("line")
                        .getAsJsonObject("args")
                        .getAsJsonObject("reference")
                        .get("value")
                        .getAsString())
                .toList();
    }

    @Test
    void aGameActionIsARowWithTheAskerOnIt() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO smp_milestone (key, state, unlocked) VALUES ('r-open', 'ACTIVE', NULL)");
        }
        try {
            final HttpResponse<String> asked = post("/api/smp/milestone", "{\"key\": \"r-open\"}");
            assertEquals(202, asked.statusCode(), asked.body());
            final String id =
                    GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString();

            final JsonObject outcome = GSON.fromJson(get("/api/commands/" + id).body(), JsonObject.class);
            assertEquals("PENDING", outcome.get("status").getAsString());

            final java.util.Map<String, String> row = row(id);
            assertEquals("UNLOCK_MILESTONE", row.get("kind"));
            assertEquals("PERSON", row.get("actor_kind"));
            assertEquals("1", row.get("actor_id"));
            assertEquals(GSON.fromJson("{\"key\": \"r-open\"}", JsonObject.class), payload(row));
        } finally {
            sql("DELETE FROM smp_inbox WHERE id IN (SELECT request FROM smp_unlock_milestone WHERE key = 'r-open')");
            sql("DELETE FROM smp_milestone WHERE key = 'r-open'");
        }
    }

    /** An SMP jar with a welcome to the game, whose player has an example, and a link message on Discord. */
    private Path welcomeJar() throws Exception {
        final Path jar = agent.configs.resolve("smp/smp-0.9.1.jar");
        PluginJars.smp(
                jar,
                Map.of(
                        "messages/smp/en.properties",
                        "welcome=Welcome {player}\nlinked=Linked\n",
                        "messages/smp/schema.json",
                        """
                {"bundle": "smp", "messages": [
                  {"key": "welcome", "name": "Welcome", "section": [], "format": "MINIMESSAGE", "shown": ["CHAT"],
                   "args": [{"name": "player", "kind": "name", "example": "Alex", "action": false}]},
                  {"key": "linked", "name": "Linked", "section": [], "format": "DISCORD_MARKDOWN",
                   "shown": ["DISCORD_EMBED"], "args": []}],
                 "contexts": {}, "globals": []}
                """));
        return jar;
    }

    @Test
    void aPreviewGoesToTheAdminsPlayerWhereTheRosterHasThemOrToTheirDirectMessagesAndIsJournaledNowhere()
            throws Exception {
        final Path jar = welcomeJar();
        final String welcome = """
                {"bundle": "smp/smp", "key": "welcome", "language": "en", "text": "Hi {player}",
                 "shown": "CHAT", "values": {"player": "Ally"}}
                """;
        final String player = "00000000-0000-0000-0000-0000000000a1";
        final long journal = count("SELECT count(*) FROM journal_entry");
        try {
            assertEquals(409, post("/api/message-preview", welcome).statusCode(), "no player is linked");
            sql("INSERT INTO discord_user (discord_id) VALUES ('1') ON CONFLICT DO NOTHING");
            sql("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES ('1', '" + player + "', 'Ally')");
            assertEquals(409, post("/api/message-preview", welcome).statusCode(), "the player is in no game");
            sql("INSERT INTO online_player (mc_uuid, mc_name, subject, updated) VALUES ('" + player
                    + "', 'Ally', 'smp', now())");

            final HttpResponse<String> shown = post("/api/message-preview", welcome);
            assertEquals(202, shown.statusCode(), shown.body());
            final String inGame =
                    GSON.fromJson(shown.body(), JsonObject.class).get("id").getAsString();
            assertTrue(inGame.startsWith("smp:"), inGame);
            assertEquals("PREVIEW_MESSAGE", row(inGame).get("kind"));
            final String request = inGame.substring("smp:".length());
            assertEquals(
                    1,
                    count("SELECT count(*) FROM smp_preview_message p JOIN smp_preview_message_example e"
                            + " USING (request) WHERE request = " + request + " AND p.player = '" + player
                            + "' AND p.key = 'welcome' AND e.name = 'player' AND e.value = 'Ally'"));

            final HttpResponse<String> sent = post(
                    "/api/message-preview",
                    "{\"bundle\": \"smp/smp\", \"key\": \"linked\", \"language\": \"en\", \"text\": \"**Linked**\","
                            + " \"shown\": \"DISCORD_EMBED\"}");
            assertEquals(202, sent.statusCode(), sent.body());
            final String dm =
                    GSON.fromJson(sent.body(), JsonObject.class).get("id").getAsString();
            assertTrue(dm.startsWith("bot:"), dm);
            assertEquals("PREVIEW_MESSAGE", row(dm).get("kind"));
            assertEquals(
                    "PENDING",
                    GSON.fromJson(get("/api/commands/" + dm).body(), JsonObject.class)
                            .get("status")
                            .getAsString());
            assertEquals(journal, count("SELECT count(*) FROM journal_entry"), "a preview changes nothing");
        } finally {
            sql("DELETE FROM online_player WHERE mc_uuid = '" + player + "'");
            sql("DELETE FROM account_link WHERE discord_id = '1'");
            Files.delete(jar);
        }
    }

    /** An aura or spins set is a row naming the person, journaled about them with the value asked for. */
    @Test
    void anAuraOrSpinsSetIsARowAboutThePersonWithItsJournalLine() throws Exception {
        final String person = "700000000000000042";
        sql("INSERT INTO discord_user (discord_id) VALUES ('" + person + "')");
        try {
            assertRow(
                    post("/api/people/" + person + "/aura", "{\"aura\": -25}"),
                    "SMP",
                    "SET_AURA",
                    "{\"person\":\"" + person + "\",\"aura\":-25}");
            assertRow(
                    post("/api/people/" + person + "/spins", "{\"spins\": 3}"),
                    "SMP",
                    "SET_SPINS",
                    "{\"person\":\"" + person + "\",\"spins\":3}");
            assertEquals(
                    "SET_AURA PERSON 1 -25,SET_SPINS PERSON 1 3",
                    text("SELECT string_agg(e.action || ' ' || e.actor_kind || ' ' || e.actor_id || ' '"
                            + " || coalesce(a.aura, s.spins), ',' ORDER BY e.action) FROM journal_entry e"
                            + " LEFT JOIN journal_aura a ON a.entry = e.id LEFT JOIN journal_spins s ON s.entry = e.id"
                            + " WHERE e.subject = '" + person + "'"));

            final long before = commandRequestCount();
            assertEquals(
                    400,
                    post("/api/people/" + person + "/aura", "{\"aura\": 1000001}")
                            .statusCode());
            assertEquals(
                    400,
                    post("/api/people/" + person + "/aura", "{\"aura\": 1.5}").statusCode());
            assertEquals(
                    400,
                    post("/api/people/" + person + "/spins", "{\"spins\": -1}").statusCode());
            assertEquals(400, post("/api/people/" + person + "/spins", "{}").statusCode());
            assertEquals(
                    404,
                    post("/api/people/700000000000000043/spins", "{\"spins\": 1}")
                            .statusCode());
            assertEquals(404, post("/api/people/nobody/aura", "{\"aura\": 1}").statusCode());
            assertEquals(before, commandRequestCount(), "a refused set was written into the inbox anyway");
        } finally {
            sql("DELETE FROM journal_entry WHERE subject = '" + person + "'");
            sql("DELETE FROM discord_user WHERE discord_id = '" + person + "'");
        }
    }

    private static String text(final String query) throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

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
            if (row.get("payload") == null) {
                try (var kind = connection.prepareStatement("SELECT cast(to_jsonb(k) - 'request' AS text) FROM "
                        + name[0] + "_" + row.get("kind").toLowerCase(java.util.Locale.ROOT)
                        + " k WHERE request = ?")) {
                    kind.setLong(1, Long.parseLong(name[1]));
                    try (var rows = kind.executeQuery()) {
                        assertTrue(rows.next(), "the kind's row is not there: " + id);
                        row.put("payload", rows.getString(1));
                    }
                }
            }
        }
        return row;
    }

    private static JsonObject payload(final java.util.Map<String, String> row) {
        return GSON.fromJson(row.get("payload"), JsonObject.class);
    }

    /**
     * Tests that the pages ask for a service by what they act on, never by command name.
     *
     * The whole track is read. Only the active milestone and its open objectives can be acted on.
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

            assertRefused(
                    post("/api/smp/objective", "{\"key\":\"t-coal\"}"), ServerRefusal.NO_SUCH_OBJECTIVE.with("t-coal"));
            assertRefused(
                    post("/api/smp/objective", "{\"key\":\"t-gold\"}"), ServerRefusal.NO_SUCH_OBJECTIVE.with("t-gold"));
            assertEquals(400, post("/api/smp/objective", "{}").statusCode());
            assertRefused(
                    post("/api/smp/milestone", "{\"key\":\"t-later\"}"),
                    ServerRefusal.MILESTONE_NOT_ACTIVE.with("t-later", "t-open"));
            assertRefused(
                    post("/api/smp/milestone", "{\"key\":\"t-done\"}"),
                    ServerRefusal.MILESTONE_NOT_ACTIVE.with("t-done", "t-open"));

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
                statement.execute("DELETE FROM smp_inbox"
                        + " WHERE id IN (SELECT request FROM smp_unlock_milestone WHERE key LIKE 't-%')");
                statement.execute("DELETE FROM smp_milestone WHERE key LIKE 't-%'");
            }
        }
    }

    /** The start request holds whether the admin confirmed it. The server needs that below the recommended minimum. */
    @Test
    void theHungerGamesStartIsARowSayingWhetherItWasConfirmed() throws Exception {
        assertEquals("{}", get("/api/hunger-games/round").body());
        final RoundSeed seed = new RoundSeed(WebFixture.postgres.dataSource());
        final UUID registration = seed.round("OPEN");
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

            seed.execute("UPDATE registration SET state = 'CLOSED'");
            seed.game(registration, "RUNNING");
            assertEquals(
                    "RUNNING",
                    GSON.fromJson(get("/api/hunger-games/round").body(), JsonObject.class)
                            .get("state")
                            .getAsString());
        } finally {
            try (var connection = WebFixture.postgres.dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM registration");
            }
        }
    }

    /** One form sends one request with every language, and nothing is written while a language lacks its text. */
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

        // Stands in for the bot: posts English and finds no channel for German.
        final long asked = Long.parseLong(ids.get("en").getAsString().replaceAll("\\D", ""));
        eu.nordtal.season.database.inbox.Inbox.over(
                        WebFixture.postgres.dataSource(), eu.nordtal.season.database.inbox.BotRequest.TABLE)
                .drain(request -> request.id() == asked
                        ? eu.nordtal.season.database.inbox.Outcome.done(
                                new eu.nordtal.season.database.inbox.BotRequest.Announce.Answer(List.of(
                                        new eu.nordtal.season.database.inbox.BotRequest.Announce.Posted("de", false),
                                        new eu.nordtal.season.database.inbox.BotRequest.Announce.Posted("en", true))))
                        : eu.nordtal.season.database.inbox.Outcome.failed(null));
        final java.util.Map<String, JsonObject> results = new java.util.HashMap<>();
        for (final String tag : List.of("en", "de")) {
            final JsonObject line = GSON.fromJson(
                    get("/api/commands/" + ids.get(tag).getAsString()).body(), JsonObject.class);
            assertEquals("DONE", line.get("status").getAsString(), line.toString());
            results.put(tag, line.getAsJsonObject("result"));
        }
        assertTrue(results.get("en").toString().contains("true"), results.toString());
        assertTrue(results.get("de").toString().contains("false"), results.toString());
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
        // getAsString() on a JsonArray or JsonObject throws, and Javalin turns that into a 500, not a 400.
        final long before = commandRequestCount();

        final HttpResponse<String> asList = post("/api/smp/milestone", "{\"key\": [\"a\", \"b\"]}");
        assertEquals(400, asList.statusCode(), asList.body());

        final HttpResponse<String> asObject = post("/api/smp/milestone", "{\"key\": {\"a\": 1}}");
        assertEquals(400, asObject.statusCode(), asObject.body());

        // Neither is a row, because a 400 that had already written the request would be the same bug.
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

    /** A stale key is refused as the SMP refuses it: 409, its reason and the sentence of the database bundle. */
    private static void assertRefused(final HttpResponse<String> answer, final Refusal refusal) {
        assertEquals(409, answer.statusCode(), answer.body());
        final JsonObject body = GSON.fromJson(answer.body(), JsonObject.class);
        assertEquals(refusal.reason().name(), body.get("code").getAsString(), answer.body());
        assertEquals(DatabaseText.english(refusal.message()), body.get("error").getAsString(), answer.body());
    }
}
