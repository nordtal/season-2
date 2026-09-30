package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.auth.TestAuthenticator;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Recreating a service through steward-deployer, and the truncated actor edge case around it. */
class DeployerApiTest extends StewardUiTestSupport {

    @Test
    void aRecreateReachesTheDeployer() throws Exception {
        recreated.clear();

        final HttpResponse<String> accepted = post("/api/deployer/recreate/smp", "");

        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals(
                List.of("smp"),
                recreated,
                "the stand-in deployer refuses any token but its own, so arriving at all is the "
                        + "assertion: the interface sent the deployer's secret and not the worker's");
        assertTrue(accepted.body().contains("job-1"), accepted.body());
    }

    @Test
    void aRecreateIsWrittenDown() throws Exception {
        post("/api/deployer/recreate/limbo", "");

        final JsonArray journal =
                GSON.fromJson(get("/api/journal?action=RECREATE").body(), JsonArray.class);
        final JsonObject row = journal.get(0).getAsJsonObject();
        assertEquals("RECREATE", row.get("action").getAsString());
        // The Discord id is what `audit_log.actor` holds, since "name (id)" overflowed varchar(32).
        assertEquals("1", row.get("actor").getAsString());
        assertTrue(
                row.get("detail").getAsString().contains("Ally"),
                row.get("detail").getAsString());
        assertEquals("limbo", row.get("subject").getAsString());
    }

    /**
     * Every path into {@code audit_log.actor}, a {@code varchar(32)}, signed in with a 32-character nickname.
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
            assertCommandAndRecreateAreJournalledByFullName(browser, snowflake, longName);
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

    private void assertCommandAndRecreateAreJournalledByFullName(
            final HttpClient browser, final String snowflake, final String longName) throws Exception {
        // 1. An announcement: the request and its journal line are one transaction, so an overflow loses both.
        final HttpResponse<String> asked = post(
                browser,
                "/api/announcements",
                "{\"texts\": {\"en\": \"The end opens tonight.\", \"de\": \"Heute Abend.\"}}");
        assertEquals(202, asked.statusCode(), asked.body());
        journalledBy(snowflake, "ANNOUNCE", longName);

        // 2. A recreate. Journalled before the call, so an overflow stops the recreate from being asked.
        assertEquals(202, post(browser, "/api/deployer/recreate/smp", "").statusCode());
        journalledBy(snowflake, "RECREATE", longName);
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
        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from bot_inbox where payload ->> 'person' = '555000000000000001'");
        }
    }

    private void assertSeasonDatesAndPhaseAreJournalled(final HttpClient browser, final String snowflake)
            throws Exception {
        // 5. The two season dates and the phase; PhaseDao casts the actor to varchar(32) and truncates it.
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        final String phaseBefore = season.get("phase").getAsString();
        final String launchAt = season.has("launch") ? season.get("launch").getAsString() : "2026-10-01T18:00:00Z";
        final String smpStartAt =
                season.has("smpStart") ? season.get("smpStart").getAsString() : "2026-10-01T18:00:00Z";

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

    @Test
    void theDeployerIsNotOnItsOwnList() throws Exception {
        recreated.clear();

        final HttpResponse<String> refused = post("/api/deployer/recreate/steward-deployer", "");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(recreated.isEmpty(), "the request must not have left this process");
        assertTrue(refused.body().contains("setup script"), refused.body());
    }

    @Test
    void aNameThatIsAPathIsRefused() throws Exception {
        recreated.clear();

        final HttpResponse<String> refused = post("/api/deployer/recreate/smp%2F..%2Fjobs", "");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(recreated.isEmpty(), "the request must not have left this process");
    }

    @Test
    void aJobIsReadBack() throws Exception {
        final JsonObject job = GSON.fromJson(get("/api/deployer/jobs/job-1").body(), JsonObject.class);

        assertEquals("DONE", job.get("state").getAsString());
        assertEquals(0, job.get("exitCode").getAsInt());
        assertTrue(job.getAsJsonArray("lines").toString().contains("Recreated"), job.toString());
    }
}
