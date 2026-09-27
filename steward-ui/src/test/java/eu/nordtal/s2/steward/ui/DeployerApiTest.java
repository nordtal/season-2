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

/**
 * Recreating a service through steward-deployer, and the truncated-actor edge case around it.
 */
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
        // The Discord id is what `audit_log.actor` holds; "name (id)" used to overflow varchar(32).
        assertEquals("1", row.get("actor").getAsString());
        assertTrue(
                row.get("detail").getAsString().contains("Ally"),
                row.get("detail").getAsString());
        assertEquals("limbo", row.get("subject").getAsString());
    }

    /**
     * The column that could not hold what was being written into it, on every path that writes it.
     *
     * Why one test and not five
     * It is one mistake, made five times, and it has one shape: a composed {@code "name (id)"} put
     * into {@code audit_log.actor}, which is {@code varchar(32)} and is documented as the admin's
     * Discord id. A snowflake is 17 to 19 digits, so the brackets and the id alone are 20 to 22
     * characters; any display name of eleven characters or more overflowed. Splitting this into
     * five tests would let four of them stay green while the fifth path was reintroduced, and the
     * thing worth asserting is that no route into this journal composes any more.
     *
     * Why it needs its own sign-in
     * Every other test in this class is signed in as {@code Ally (1)} - eight characters, which
     * fits with room to spare and is exactly why nothing here saw the bug for as long as it
     * existed. This one signs in a second browser against the same real flow with the stand-in
     * Discord answering a 19-digit snowflake and a 32-character nickname: the maximum Discord
     * allows, which is the case that has to work rather than a case that happens to.
     *
     * The phase path is the quiet one
     * {@code PhaseDao} writes its own journal row with {@code cast(:actor AS varchar(32))}, and an
     * explicit cast in PostgreSQL truncates rather than refusing. That path therefore never
     * failed; it wrote half a name into the journal and said nothing, which is worse than the 500
     * the other four gave. So the assertion there is on the value, not on the status code.
     */
    @Test
    void aLongDisplayNameIsNotAnOverflow() throws Exception {
        final String snowflake = "1234567890123456789";
        final String longName = "Archibald Fotheringay-Chumleighs";
        assertEquals(19, snowflake.length(), "a Discord snowflake is 17 to 19 digits");
        assertEquals(32, longName.length(), "32 is the longest nickname Discord accepts");
        // What the old form would have produced, and what the column is: the arithmetic, spelled out.
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
        // 1. A command: the row and its journal line are one statement, so an overflow loses the command.
        final HttpResponse<String> asked = post(
                browser,
                "/api/commands",
                "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": \"aufbruch\"}}");
        assertEquals(202, asked.statusCode(), asked.body());
        journalledBy(snowflake, "COMMAND", longName);

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
                count("select count(*) from access_request where subject ="
                        + " '555000000000000001' and requested_by = '" + snowflake + "'"));
        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from access_request where subject = '555000000000000001'");
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
