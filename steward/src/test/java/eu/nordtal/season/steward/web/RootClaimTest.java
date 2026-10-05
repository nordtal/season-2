package eu.nordtal.season.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Who may become the root of an empty admin tree: the account {@code discord.root-id} names, and nobody else.
 *
 * Every test starts from a tree with nobody in it, as after a fresh install or the restore of an early dump.
 */
class RootClaimTest extends WebFixture {

    @AfterEach
    void emptyTheTreeAgain() throws Exception {
        memberId.set("1");
        fakeDiscord.rootId.set("1");
        try (var connection = postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "UPDATE discord_user SET admin = false, admin_granted_by = NULL, admin_granted_at = NULL");
        }
    }

    @Test
    void theRootIdsAccountClaimsTheEmptyTree() throws Exception {
        final HttpResponse<String> callback = signInAs("1");

        assertEquals(302, callback.statusCode(), callback.body());
        assertEquals(
                1,
                WebTestSupport.count("SELECT count(*) FROM discord_user WHERE discord_id = '1' AND admin"
                        + " AND admin_granted_by IS NULL"));
    }

    @Test
    void anotherAccountIsRefusedAndTheTreeStaysEmpty() throws Exception {
        final HttpResponse<String> callback = signInAs("2");

        assertEquals(403, callback.statusCode(), callback.body());
        assertTrue(callback.body().contains("not an admin"), callback.body());
        assertEquals(0, WebTestSupport.count("SELECT count(*) FROM discord_user WHERE admin"));
    }

    @Test
    void withoutARootIdNobodyCanSignInAndTheSignInPageSaysSo() throws Exception {
        fakeDiscord.rootId.set("");
        final HttpClient browser = WebTestSupport.browser();

        final JsonObject me =
                GSON.fromJson(WebTestSupport.get(browser, "/api/me").body(), JsonObject.class);
        assertTrue(me.get("signInUnavailable").getAsString().contains("discord.root-id"), me.toString());
        final HttpResponse<String> login = WebTestSupport.get(browser, "/auth/login");
        assertEquals(503, login.statusCode(), login.body());
        assertEquals(0, WebTestSupport.count("SELECT count(*) FROM discord_user WHERE admin"));
    }

    @Test
    void aTreeWithSomebodyInItNeedsNoRootId() throws Exception {
        assertEquals(302, signInAs("1").statusCode());
        fakeDiscord.rootId.set("");

        final JsonObject me = GSON.fromJson(
                WebTestSupport.get(WebTestSupport.browser(), "/api/me").body(), JsonObject.class);
        assertFalse(me.has("signInUnavailable"), me.toString());
    }

    /** The sign-in of one Discord account, as far as the callback's answer. */
    private static HttpResponse<String> signInAs(final String discordId) throws Exception {
        memberId.set(discordId);
        final HttpClient browser = WebTestSupport.browser();
        final String state = WebTestSupport.stateFrom(WebTestSupport.get(browser, "/auth/login"));
        return WebTestSupport.get(browser, "/auth/callback?code=" + StandInDiscord.CODE + "&state=" + state);
    }
}
