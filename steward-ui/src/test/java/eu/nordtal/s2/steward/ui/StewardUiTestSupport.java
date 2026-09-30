package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.auth.TestAuthenticator;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;

/** The HTTP and database helpers every steward-ui integration test drives the fixture through. */
abstract class StewardUiTestSupport extends StewardUiFixture {

    @BeforeAll
    static void signInAsRoot() throws Exception {
        http = browser();
        signIn(http);
        // The first key, and every other test in this class rides on it: no key, no /api/me.
        registerAKey(http, authenticator, "The test key");
    }

    /** Closes whatever run another test left open, so the one-run rule starts every test clean. */
    void settleOpenRuns() throws Exception {
        try (var connection = data.dataSource().getConnection();
                var settle = connection.prepareStatement("UPDATE update_request SET status = 'DONE', "
                        + "started = coalesce(started, now()), finished = now() "
                        + "WHERE status IN ('PENDING', 'RUNNING')")) {
            settle.executeUpdate();
        }
    }

    static long count(final String sql) throws Exception {
        try (var connection = data.dataSource().getConnection();
                var statement = connection.createStatement();
                var row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** The newest journal row of this action, written by that id and naming the person in its detail. */
    static void journalledBy(final String id, final String action, final String name) throws Exception {
        final JsonObject row = newestJournalRow(action);
        assertEquals(
                id,
                row.get("actor").getAsString(),
                action + " was journalled as something other than the bare Discord id: " + row);
        // The name moved to `detail`, which is `text` and has room for it.
        assertTrue(
                row.get("detail").getAsString().contains(name),
                action + " lost the name entirely instead of moving it into the detail: " + row);
    }

    static String actorOf(final String action) throws Exception {
        return newestJournalRow(action).get("actor").getAsString();
    }

    static JsonObject newestJournalRow(final String action) throws Exception {
        final JsonArray journal =
                GSON.fromJson(get("/api/journal?action=" + action).body(), JsonArray.class);
        assertFalse(journal.isEmpty(), "nothing was journalled as " + action);
        return journal.get(0).getAsJsonObject();
    }

    /** The whole authentication, driven the way the browser drives it. */
    static void holdTheKey(final HttpClient browser, final TestAuthenticator key) throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final HttpResponse<String> finished = finishAssertion(browser, key.assertion(started.body(), ORIGIN));
        assertEquals(200, finished.statusCode(), finished.body());
    }

    static HttpResponse<String> finishAssertion(final HttpClient browser, final String credential) throws Exception {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("credential", credential);
        return post(browser, "/auth/webauthn/authenticate/finish", GSON.toJson(envelope));
    }

    /** Moves this browser's key ceremony back out of the five-minute window, by the row rather than a clock. */
    static void heldLongAgo(final HttpClient browser) throws Exception {
        final String csrf = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("csrf")
                .getAsString();
        try (var connection = data.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE steward_session SET verified_at = now() - interval '1 hour'" + " WHERE csrf = ?")) {
            statement.setString(1, csrf);
            assertEquals(1, statement.executeUpdate(), "no session matched that browser");
        }
    }

    static HttpResponse<String> delete(final HttpClient browser, final String path) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + path))
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> put(final HttpClient browser, final String path, final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + path))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The whole registration, driven the way the browser drives it. */
    static void registerAKey(final HttpClient browser, final TestAuthenticator key, final String label)
            throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/webauthn/register/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final HttpResponse<String> finished = finishRegistration(browser, key.register(started.body(), ORIGIN), label);
        assertEquals(200, finished.statusCode(), finished.body());
    }

    /** The finish, with the credential as a string inside the envelope, since only the library may parse it. */
    static HttpResponse<String> finishRegistration(
            final HttpClient browser, final String credential, final String label) throws Exception {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("label", label);
        envelope.addProperty("credential", credential);
        return post(browser, "/auth/webauthn/register/finish", GSON.toJson(envelope));
    }

    /** One browser: its own cookie jar, and no following of redirects, which a test reads. */
    static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    /**
     * The whole sign-in, driven the way a browser drives it, for an account made an admin first.
     *
     * The first sign-in claims the root; others are made admins by SQL, since a grant is limited to three an hour.
     */
    static void signIn(final HttpClient browser) throws Exception {
        if (!"1".equals(memberId.get())) {
            admitBelowRoot(DiscordId.of(memberId.get()));
        }
        final String state = stateFrom(get(browser, "/auth/login"));

        final HttpResponse<String> callback = get(browser, "/auth/callback?code=the-code&state=" + state);

        assertEquals(302, callback.statusCode(), callback.body());
        assertEquals("/", callback.headers().firstValue("Location").orElseThrow());
    }

    /** Makes this account an admin granted by the root, {@code "1"}, whatever it was before. */
    static void admitBelowRoot(final DiscordId discordId) throws Exception {
        try (var connection = data.dataSource().getConnection();
                var admit = connection.prepareStatement("""
                     INSERT INTO discord_user (discord_id, member_state, admin, admin_granted_by,
                                               admin_granted_at, updated)
                     VALUES (?, 'MEMBER', true, '1', now(), now())
                     ON CONFLICT (discord_id) DO UPDATE
                         SET member_state = 'MEMBER', admin = true, admin_granted_by = '1',
                             admin_granted_at = now(), updated = now()
                     """)) {
            admit.setString(1, discordId.value());
            admit.executeUpdate();
        }
    }

    /** The one-time value the interface minted into the URL it sent the browser to. */
    static String stateFrom(final HttpResponse<String> redirect) {
        assertEquals(302, redirect.statusCode(), redirect.body());
        final String location = redirect.headers().firstValue("Location").orElseThrow();
        final Matcher state = Pattern.compile("[?&]state=([^&]+)").matcher(location);
        assertTrue(state.find(), location);
        return state.group(1);
    }

    /** The value of the session cookie this response set, or a failure naming what it did set. */
    static String sessionCookieOf(final HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(Sessions.COOKIE + "="))
                .map(header -> header.substring(Sessions.COOKIE.length() + 1).split(";", 2)[0])
                .findFirst()
                .orElseGet(() -> fail("this response set no session cookie: "
                        + response.headers().map()));
    }

    /**
     * Stops the interface and starts a new one against the same database, sharing nothing but the rows.
     *
     * The old {@code Data} stays open, since other tests still hold sessions in its pool.
     */
    static void restartTheInterface() throws Exception {
        ui.stop();
        ui = new StewardUi(
                config,
                new DiscordAuth(config.discord(), config.publicUrl(), "http://127.0.0.1:" + DISCORD_PORT),
                new InternalClient(
                        "steward-worker",
                        config.worker().baseUrl(),
                        config.worker().token(),
                        Duration.ofSeconds(5)),
                new InternalClient(
                        "steward-deployer",
                        config.deployer().baseUrl(),
                        config.deployer().token(),
                        Duration.ofSeconds(5)),
                data,
                Clock.systemUTC());
        ui.start(UI_PORT);
    }

    static HttpResponse<String> logout(final HttpClient browser) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + "/auth/logout"))
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(final String path, final String body) throws Exception {
        return post(http, path, body);
    }

    static HttpResponse<String> post(final HttpClient browser, final String path, final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + path))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonObject entry(final JsonObject document, final String path) {
        return document.getAsJsonArray("entries").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(entry -> entry.get("path").getAsString().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + " is not in " + document));
    }

    static HttpResponse<String> put(final String path, final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + path))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(final String path) throws Exception {
        return get(http, path);
    }

    static HttpResponse<String> get(final HttpClient browser, final String path) throws Exception {
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UI_PORT + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
