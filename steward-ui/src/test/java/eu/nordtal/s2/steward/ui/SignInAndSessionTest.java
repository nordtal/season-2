package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Signing in against the stand-in Discord, the session it leaves behind, and admin grants.
 *
 * The flow is real end to end, from state parameter to CSRF token; only {@code discord.com} is stood in for.
 */
class SignInAndSessionTest extends StewardUiTestSupport {

    @Test
    void theBundleIsReadableWithoutASession() throws Exception {
        // `guard` runs on `beforeMatched`, which also runs in front of static files with no Gate.
        final HttpClient stranger = browser();
        final HttpResponse<String> page = get(stranger, "/");

        assertEquals(200, page.statusCode(), "the page a person opens before signing in: " + page.body());
        assertTrue(
                page.body().contains("<div id=\"root\""),
                "that should be index.html, not an error page: " + page.body());

        // The deep path a reload lands on, which takes the SPA fallback rather than the file handler.
        final HttpResponse<String> deep = get(stranger, "/operations/updates/27");
        assertEquals(200, deep.statusCode(), "reloading a deep link must land on the page it names: " + deep.body());

        // A file next to it, because the browser asks for these before anybody clicks anything.
        assertEquals(200, get(stranger, "/favicon.ico").statusCode());

        // The fallback is a greedy route; a real asset still has to arrive as itself, not as index.html.
        final String index = page.body();
        final int asset = index.indexOf("/assets/");
        assertTrue(asset > 0, "index.html should reference a built asset: " + index);
        final String assetPath = index.substring(asset, index.indexOf('"', asset));
        final HttpResponse<String> built = get(stranger, assetPath);
        assertEquals(200, built.statusCode(), assetPath);
        assertFalse(built.body().contains("<div id=\"root\""), assetPath + " came back as the page instead of itself");

        // A hashed bundle never changes under its own name, so it caches forever.
        assertEquals(
                List.of("max-age=31536000, immutable"),
                built.headers().allValues("Cache-Control"),
                assetPath + " is content-hashed and must be told to cache forever: "
                        + built.headers().map());
        assertEquals(
                List.of("no-cache"),
                page.headers().allValues("Cache-Control"),
                "/ carries no hash in its name and must always be revalidated: "
                        + page.headers().map());
        assertEquals(
                List.of("no-cache"),
                deep.headers().allValues("Cache-Control"),
                "a client-side route falls back to the same document and needs the same header: "
                        + deep.headers().map());

        // An endpoint that does not exist answers 404, not 200 with HTML, or a JSON caller reports a parse error.
        assertEquals(404, get(stranger, "/api/there-is-no-such-thing").statusCode());
    }

    @Test
    void signedOutIsNotHalfway() throws Exception {
        // A browser with its own empty cookie jar, which is what "signed out" actually is.
        final HttpClient stranger = browser();

        assertEquals(401, get(stranger, "/api/services").statusCode());

        final JsonObject me = GSON.fromJson(get(stranger, "/api/me").body(), JsonObject.class);
        assertFalse(me.get("signedIn").getAsBoolean());
        // Configured here, so nothing is missing; DiscordAuthTest covers the deployment where something is.
        assertFalse(me.has("signInUnavailable"), me.toString());
        // This sentence is printed on the sign-in page, so it has to stay true as the second factor evolves.
        final String said = me.get("webauthn").getAsString();
        assertTrue(said.contains("required"), "the sign-in page no longer says a key is needed: " + said);
        assertFalse(
                said.contains("not built"),
                "the sign-in page still says the second factor" + " does not exist, which stopped being true with V20: "
                        + said);
        assertFalse(
                said.contains("not yet"),
                "the sign-in page still says the key is not yet"
                        + " asked for before a dangerous action, which stopped being true with packages C"
                        + " and D: " + said);
        assertTrue(
                said.contains("every sign-in"),
                "the sign-in page does not say the key is asked"
                        + " for at every sign-in, which is the whole of package C: " + said);
    }

    @Test
    void theRegistrationDoorAnswersTheRightRefusal() throws Exception {
        // The two register routes are outside /api/*, so the filter never sees them and repeats its checks by hand.
        final HttpClient stranger = browser();
        for (final String path : new String[] {"/auth/webauthn/register/start", "/auth/webauthn/register/finish"}) {
            final HttpResponse<String> refused = stranger.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, refused.statusCode(), path + " answered " + refused.body());
            assertTrue(refused.body().contains("sign in first"), refused.body());
        }
    }

    @Test
    void theSessionIsTheCookieAndNothingElse() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        assertTrue(me.get("signedIn").getAsBoolean(), me.toString());
        assertEquals("1", me.get("id").getAsString());
        // The nickname from the guild, not the username: the name the other admins know them by.
        assertEquals("Ally", me.get("name").getAsString());
        assertEquals(
                "a-client-secret",
                secretDiscordSaw.get(),
                "the code was exchanged with the application's secret, not without one");

        assertFalse(GSON.fromJson(get(browser(), "/api/me").body(), JsonObject.class)
                .get("signedIn")
                .getAsBoolean());
    }

    @Test
    void whoAmICarriesTheDiscordAvatar() throws Exception {
        // "1" was never mirrored a Discord profile, so the answer must not carry the field at all.
        final JsonObject withoutAPicture = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        assertFalse(withoutAPicture.has("discordAvatarUrl"), withoutAPicture.toString());

        try (var connection = data.dataSource().getConnection();
                var mirror = connection.prepareStatement(
                        "UPDATE discord_user SET discord_avatar_url = ? WHERE discord_id = '1'")) {
            mirror.setString(1, "https://cdn.discordapp.com/avatars/1/a.png");
            assertEquals(1, mirror.executeUpdate(), "the root has no row to mirror onto");
        }
        try {
            final JsonObject withAPicture = GSON.fromJson(get("/api/me").body(), JsonObject.class);
            assertEquals(
                    "https://cdn.discordapp.com/avatars/1/a.png",
                    withAPicture.get("discordAvatarUrl").getAsString(),
                    withAPicture.toString());
        } finally {
            // Every other test in this class signs in as "1" and expects the fallback state; this row is the root.
            try (var connection = data.dataSource().getConnection();
                    var clearIt = connection.prepareStatement(
                            "UPDATE discord_user SET discord_avatar_url = NULL WHERE discord_id = '1'")) {
                clearIt.executeUpdate();
            }
        }
        // NULL, not empty text, is what the schema writes for a row without a picture (V21).
        final JsonObject cleared = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        assertFalse(cleared.has("discordAvatarUrl"), cleared.toString());
    }

    @Test
    void theCookieSurvivesTheAppBeingClosed() throws Exception {
        // Asserted on the wire, not on a setter: Jetty's defaults are wrong for a home-screen web app.
        final HttpClient browser = browser();
        final HttpResponse<String> login = get(browser, "/auth/login");
        final String state = stateFrom(login);
        get(browser, "/auth/callback?code=the-code&state=" + state);

        final String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(Sessions.COOKIE + "="))
                .findFirst()
                .orElseGet(() -> fail(
                        "the sign-in set no session cookie: " + login.headers().map()));

        assertTrue(
                cookie.contains("Max-Age="),
                cookie
                        + " has no Max-Age, so it is a browser-session cookie and a home-screen web app"
                        + " signs in again every time iOS has ended it");
        assertTrue(cookie.contains("HttpOnly"), cookie + " is readable from JavaScript");
        assertTrue(
                cookie.toLowerCase(Locale.ROOT).contains("samesite=lax"),
                cookie
                        + " has no SameSite, so what a browser does with it on a cross-site POST is the"
                        + " browser's default rather than this application's decision");
        // ...and not Secure, because this request arrived over plain http with no X-Forwarded-Proto.
        assertFalse(
                cookie.contains("Secure"),
                cookie + " is marked Secure on a plain http request, so a local sign-in cannot finish");

        // The other half of the same decision, which is the one the deployment runs.
        final HttpClient throughCaddy = browser();
        final HttpResponse<String> behindTls = throughCaddy.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/auth/login"))
                        .header("X-Forwarded-Proto", "https")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        final String secured = behindTls.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(Sessions.COOKIE + "="))
                .findFirst()
                .orElseGet(
                        () -> fail("no session cookie: " + behindTls.headers().map()));
        assertTrue(
                secured.contains("Secure"),
                secured
                        + " is not marked Secure although the proxy said the browser is on https, so the"
                        + " session cookie travels in clear text to anybody who can downgrade one request");
    }

    @Test
    void theSessionOutlivesTheProcess() throws Exception {
        // The whole reason steward_session exists: a restart must not sign everybody out.
        final HttpClient browser = browser();
        signIn(browser);
        assertTrue(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("signedIn")
                .getAsBoolean());

        restartTheInterface();

        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        assertTrue(
                me.get("signedIn").getAsBoolean(),
                "the browser kept its cookie and the row is still there, so this is still a " + "session: " + me);
        assertEquals("Ally", me.get("name").getAsString(), me.toString());
    }

    @Test
    void theSessionIdIsRotatedOnSignIn() throws Exception {
        // Session fixation: the id a browser carried before sign-in must not become a signed-in id after it.
        final HttpClient browser = browser();
        final HttpResponse<String> login = get(browser, "/auth/login");
        final String before = sessionCookieOf(login);

        final HttpResponse<String> callback = get(browser, "/auth/callback?code=the-code&state=" + stateFrom(login));
        final String after = sessionCookieOf(callback);

        assertNotEquals(
                before,
                after,
                "the id that was in the browser before anybody proved who they were is now a " + "signed-in session");

        // The old one is gone, so it can no longer be a valid cookie for anybody.
        final HttpClient planted = browser();
        final HttpResponse<String> withTheOldId = planted.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/api/me"))
                        .header("Cookie", Sessions.COOKIE + "=" + before)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertFalse(
                GSON.fromJson(withTheOldId.body(), JsonObject.class)
                        .get("signedIn")
                        .getAsBoolean(),
                withTheOldId.body());
    }

    @Test
    void aCallbackWithoutItsOwnStateIsRefused() throws Exception {
        final HttpClient browser = browser();
        get(browser, "/auth/login");

        final HttpResponse<String> refused = get(browser, "/auth/callback?code=the-code&state=somebody-elses");

        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(
                GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                        .get("signedIn")
                        .getAsBoolean(),
                "a refused callback must not leave a session");
    }

    @Test
    void withoutAGrantNobodyGetsIn() throws Exception {
        // Not signIn(): that helper makes the account an admin first, and this one must not be.
        memberId.set("880000000000000001");
        memberNick.set("Stranger");
        try {
            final HttpClient browser = browser();
            final String state = stateFrom(get(browser, "/auth/login"));

            final HttpResponse<String> refused = get(browser, "/auth/callback?code=the-code&state=" + state);

            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("Stranger"), refused.body());
            assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                    .get("signedIn")
                    .getAsBoolean());
            assertEquals(
                    0,
                    count("SELECT count(*) FROM discord_user" + " WHERE discord_id = '880000000000000001' AND admin"),
                    "a refused sign-in made somebody an admin");
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @Test
    void grantingAndRevokingAdmin() throws Exception {
        holdTheKey(http, authenticator);
        final List<String> ids =
                List.of("881000000000000001", "881000000000000002", "881000000000000003", "881000000000000004");
        seedGuildMembers(ids);
        try {
            assertGrantingRefusesBadRequests();
            assertGrantingAndItsRateLimit();
            assertRevokingCascadesAndRefuses();
        } finally {
            cleanUpAdminGrants();
        }
    }

    private static void seedGuildMembers(final List<String> ids) throws Exception {
        for (final String id : ids) {
            try (var connection = data.dataSource().getConnection();
                    var member = connection.prepareStatement(
                            "INSERT INTO discord_user (discord_id, member_state) VALUES (?, 'MEMBER')")) {
                member.setString(1, id);
                member.executeUpdate();
            }
        }
    }

    private void assertGrantingRefusesBadRequests() throws Exception {
        assertEquals(
                409,
                post("/api/admins/grant", "{\"discordId\":\"881999999999999999\"}")
                        .statusCode(),
                "somebody the bot never saw in the guild was made an admin");
        assertEquals(
                400,
                post("/api/admins/grant", "{\"discordId\":\"not a snowflake\"}").statusCode());
    }

    private void assertGrantingAndItsRateLimit() throws Exception {
        final HttpResponse<String> granted = post("/api/admins/grant", "{\"discordId\":\"881000000000000001\"}");
        assertEquals(200, granted.statusCode(), granted.body());
        assertEquals("1", actorOf("GRANT_ADMIN"));
        assertEquals(
                409,
                post("/api/admins/grant", "{\"discordId\":\"881000000000000001\"}")
                        .statusCode(),
                "granted twice");

        assertEquals(
                200,
                post("/api/admins/grant", "{\"discordId\":\"881000000000000002\"}")
                        .statusCode());
        assertEquals(
                200,
                post("/api/admins/grant", "{\"discordId\":\"881000000000000003\"}")
                        .statusCode());
        final HttpResponse<String> fourth = post("/api/admins/grant", "{\"discordId\":\"881000000000000004\"}");
        assertEquals(429, fourth.statusCode(), "the fourth grant in an hour went through");
    }

    private void assertRevokingCascadesAndRefuses() throws Exception {
        // One of them granted below the first, so revoking the first takes both.
        try (var connection = data.dataSource().getConnection();
                var below = connection.prepareStatement("UPDATE discord_user"
                        + " SET admin_granted_by = '881000000000000001'"
                        + " WHERE discord_id = '881000000000000002'")) {
            below.executeUpdate();
        }
        assertEquals(409, post("/api/admins/revoke", "{\"discordId\":\"1\"}").statusCode(), "the root revoked itself");
        final HttpResponse<String> revoked = post("/api/admins/revoke", "{\"discordId\":\"881000000000000001\"}");
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(
                List.of("881000000000000001", "881000000000000002"),
                GSON.fromJson(revoked.body(), JsonObject.class).getAsJsonArray("removed").asList().stream()
                        .map(com.google.gson.JsonElement::getAsString)
                        .toList());
        assertEquals("1", actorOf("REVOKE_ADMIN"));
        assertEquals(
                403,
                post("/api/admins/revoke", "{\"discordId\":\"881000000000000001\"}")
                        .statusCode(),
                "revoked somebody who is no admin any more");
        assertEquals(
                200,
                post("/api/admins/revoke", "{\"discordId\":\"881000000000000003\"}")
                        .statusCode());
    }

    private static void cleanUpAdminGrants() throws Exception {
        try (var connection = data.dataSource().getConnection();
                var cleanUp = connection.createStatement()) {
            cleanUp.executeUpdate("TRUNCATE admin_grant");
            cleanUp.executeUpdate("UPDATE discord_user SET admin = false, admin_granted_by = NULL,"
                    + " admin_granted_at = NULL WHERE discord_id LIKE '881%'");
        }
    }

    @Test
    void revokingEndsTheSession() throws Exception {
        memberId.set("882000000000000001");
        memberNick.set("Revoked");
        final HttpClient theirs = browser();
        try {
            signIn(theirs);
            assertTrue(GSON.fromJson(get(theirs, "/api/me").body(), JsonObject.class)
                    .get("signedIn")
                    .getAsBoolean());
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }

        holdTheKey(http, authenticator);
        final HttpResponse<String> revoked = post("/api/admins/revoke", "{\"discordId\":\"882000000000000001\"}");
        assertEquals(200, revoked.statusCode(), revoked.body());

        assertFalse(
                GSON.fromJson(get(theirs, "/api/me").body(), JsonObject.class)
                        .get("signedIn")
                        .getAsBoolean(),
                "the session outlived the admin it belonged to");
        assertEquals(
                0,
                count("SELECT count(*) FROM steward_session s" + " WHERE s.discord_id = '882000000000000001'"),
                "the session row is still there");
    }

    @Test
    void signingOutIsNotSomethingAnotherSiteCanDo() throws Exception {
        // /auth/logout is not under /api/*, so the write-guarding filter does not see it; a token check stands in.
        final HttpClient browser = browser();
        signIn(browser);

        final HttpResponse<String> withoutToken = browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/auth/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, withoutToken.statusCode(), withoutToken.body());
        assertTrue(
                GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                        .get("signedIn")
                        .getAsBoolean(),
                "the session survived the forged request");

        assertEquals(204, logout(browser).statusCode());
        assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("signedIn")
                .getAsBoolean());
    }
}
