package eu.nordtal.s2.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.auth.TestAuthenticator;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Recreating a service is a run like any other, and the truncated actor edge case around asking for one. */
class RecreateRunTest extends WebTestSupport {

    @Test
    void aRecreateIsEnteredAsARunAndNeverSentToTheAgent() throws Exception {
        final HttpResponse<String> accepted = post("/api/updates", "{\"kind\":\"RECREATE\",\"services\":[\"smp\"]}");
        try {
            assertEquals(202, accepted.statusCode(), accepted.body());
            assertEquals(
                    1,
                    count("select count(*) from steward_inbox where kind = 'RECREATE' and status = 'PENDING'"
                            + " and actor_id = '1'"),
                    "the recreate is a row the agent claims, written by the one who asked");
        } finally {
            forgetRuns();
        }
    }

    @Test
    void aRecreateOfNothingIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/updates", "{\"kind\":\"RECREATE\"}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertEquals(0, count("select count(*) from steward_inbox where kind = 'RECREATE'"));
    }

    /**
     * Every path into {@code audit_log.actor_id}, a {@code varchar(32)}, signed in with a 32-character nickname.
     *
     * {@code PhaseDao}'s explicit cast truncates rather than refusing, so that path asserts the value, not the status.
     */
    @Test
    void aLongDisplayNameIsNotAnOverflow() throws Exception {
        final String snowflake = "1234567890123456789";
        final String longName = "Archibald Fotheringay-Chumleighs";
        assertEquals(19, snowflake.length(), "a Discord snowflake is 17 to 19 digits");
        assertEquals(32, longName.length(), "32 is the longest nickname Discord accepts");
        // What "name (id)" would have produced, against the column's width.
        assertEquals(54, (longName + " (" + snowflake + ")").length());

        memberId.set(snowflake);
        memberNick.set(longName);
        try {
            final HttpClient browser = browser();
            signIn(browser);
            assertLongNameSurvivesSignIn(browser, longName);
            // A different account, so a different key: every /api call below is behind the gate.
            registerAKey(browser, new TestAuthenticator(), "Archibald's key");
            assertAnnouncementAndRecreateNameTheirAsker(browser, snowflake);
            assertAccessGrantAndRevokeAreJournalled(browser, snowflake);
            assertSeasonDatesAndPhaseAreJournalled(browser, snowflake);
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    private void assertLongNameSurvivesSignIn(final HttpClient browser, final String longName) throws Exception {
        assertEquals(
                longName,
                GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                        .get("name")
                        .getAsString(),
                "the stand-in did not take the long name");
    }

    private void assertAnnouncementAndRecreateNameTheirAsker(final HttpClient browser, final String snowflake)
            throws Exception {
        // 1. An announcement: the request and its journal line are one transaction, so an overflow loses both.
        final HttpResponse<String> asked = post(
                browser,
                "/api/announcements",
                "{\"texts\": {\"en\": \"The end opens tonight.\", \"de\": \"Heute Abend.\"}}");
        assertEquals(202, asked.statusCode(), asked.body());
        assertEquals(
                2,
                journalledBy(snowflake, "ANNOUNCE")
                        .getAsJsonObject("facts")
                        .getAsJsonArray("languages")
                        .size(),
                "the line names the languages it went out in, never the asker's name");

        // 2. A recreate, which is a run: the row names who asked by the Discord id, never by "name (id)".
        try {
            assertEquals(
                    202,
                    post(browser, "/api/updates", "{\"kind\":\"RECREATE\",\"services\":[\"smp\"]}")
                            .statusCode());
            assertEquals(
                    1,
                    count("select count(*) from steward_inbox where kind = 'RECREATE' and actor_id = '" + snowflake
                            + "'"));
        } finally {
            forgetRuns();
        }
    }

    private void assertAccessGrantAndRevokeAreJournalled(final HttpClient browser, final String snowflake)
            throws Exception {
        // 3. and 4. Giving access and taking it away; the bot journals those, this side writes the request.
        assertEquals(
                202,
                post(browser, "/api/access/grant", "{\"discordId\":\"555000000000000001\",\"days\":30}")
                        .statusCode());
        assertEquals(
                202,
                post(browser, "/api/access/revoke", "{\"discordId\":\"555000000000000001\"}")
                        .statusCode());
        assertEquals(
                2,
                count("select count(*) from bot_inbox where payload ->> 'person' ="
                        + " '555000000000000001' and actor_kind = 'PERSON' and actor_id = '" + snowflake + "'"));
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox where payload ->> 'person' = '555000000000000001'");
        }
    }

    private void assertSeasonDatesAndPhaseAreJournalled(final HttpClient browser, final String snowflake)
            throws Exception {
        // 5. The two season dates and the phase; PhaseDao casts the actor id to varchar(32) and would truncate it.
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        final String phaseBefore = season.get("phase").getAsString();
        final String launchAt = SOON;
        final String smpStartAt = SOON;

        assertEquals(
                200,
                post(browser, "/api/season/date", "{\"at\":\"" + launchAt + "\",\"which\":\"launch\"}")
                        .statusCode());
        assertEquals(snowflake, actorOf("SET_LAUNCH"), "the season journal took a truncated actor and said nothing");

        assertEquals(
                200,
                post(browser, "/api/season/date", "{\"at\":\"" + smpStartAt + "\",\"which\":\"smpStart\"}")
                        .statusCode());
        assertEquals(snowflake, actorOf("SET_SMP_START"));

        // START_EVENT and never SMP: setSmpStart refuses once the season is already in SMP.
        try {
            assertEquals(
                    200,
                    post(
                                    browser,
                                    "/api/season/phase",
                                    "{\"phase\":\"START_EVENT\",\"reason\":\"a long name should not matter\"}")
                            .statusCode());
            assertEquals(snowflake, actorOf("SET_PHASE"));
        } finally {
            post(
                    browser,
                    "/api/season/phase",
                    "{\"phase\":\"" + phaseBefore + "\",\"reason\":\"restoring the fixture\"}");
        }
    }

    private static void forgetRuns() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from steward_inbox where kind = 'RECREATE'");
        }
    }
}
