package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.auth.TestAuthenticator;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;

/** The HTTP and database helpers that every web integration test drives the fixture with. */
abstract class WebTestSupport extends WebFixture {

    @BeforeAll
    static void signInAsRoot() throws Exception {
        http = browser();
        signIn(http);
        // The first key. Every other test in this class rides on it: no key, no /api/me.
        registerAKey(http, authenticator, "The test key");
    }

    void settleOpenRuns() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var settle = connection.prepareStatement("UPDATE steward_inbox SET status = 'DONE', "
                        + "started = coalesce(started, now()), finished = now() "
                        + "WHERE status IN ('PENDING', 'RUNNING')")) {
            settle.executeUpdate();
        }
    }

    static void sql(final String statement) throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var run = connection.createStatement()) {
            run.execute(statement);
        }
    }

    static long count(final String sql) throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement();
                var row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** Returns the newest journal row of this action, after checking that this person is its actor. */
    static JsonObject journalledBy(final String id, final String action) throws Exception {
        final JsonObject row = newestJournalRow(action);
        final JsonObject actor = row.getAsJsonObject("actor");
        assertEquals("PERSON", actor.get("kind").getAsString(), action + " was not filed under a person: " + row);
        assertEquals(id, actor.get("person").getAsString(), action + " was filed under somebody else: " + row);
        return row;
    }

    static String actorOf(final String action) throws Exception {
        return newestJournalRow(action).getAsJsonObject("actor").get("person").getAsString();
    }

    static JsonObject newestJournalRow(final String action) throws Exception {
        final JsonArray items = GSON.fromJson(get("/api/journal?limit=200").body(), JsonObject.class)
                .getAsJsonArray("items");
        return items.asList().stream()
                .map(item -> item.getAsJsonObject().get("entry"))
                .filter(entry -> entry != null && entry.isJsonObject())
                .map(entry -> entry.getAsJsonObject())
                .filter(entry -> action.equals(entry.get("action").getAsString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing was journalled as " + action));
    }

    static void holdTheKey(final HttpClient browser, final TestAuthenticator key) throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final HttpResponse<String> finished = finishAssertion(browser, key.assertion(started.body(), ORIGIN));
        assertEquals(200, finished.statusCode(), finished.body());
    }

    static void holdTheKey(final HttpClient browser, final TestAuthenticator key, final String hold) throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("credential", key.assertion(started.body(), ORIGIN));
        envelope.addProperty("hold", hold);
        final HttpResponse<String> finished =
                post(browser, "/auth/webauthn/authenticate/finish", GSON.toJson(envelope));
        assertEquals(200, finished.statusCode(), finished.body());
    }

    static HttpResponse<String> finishAssertion(final HttpClient browser, final String credential) throws Exception {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("credential", credential);
        return post(browser, "/auth/webauthn/authenticate/finish", GSON.toJson(envelope));
    }

    /** Moves the key ceremony of this browser back out of its step-up, by the row rather than a clock. */
    static void heldLongAgo(final HttpClient browser) throws Exception {
        final String csrf = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("csrf")
                .getAsString();
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE steward_session SET verified_at = now() - interval '1 hour',"
                                + " step_up_until = now() - interval '1 minute' WHERE csrf = ?")) {
            statement.setString(1, csrf);
            assertEquals(1, statement.executeUpdate(), "no session matched that browser");
        }
    }

    static HttpResponse<String> delete(final HttpClient browser, final String path) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> put(final HttpClient browser, final String path, final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
                        .header("Content-Type", "application/json")
                        .header("X-Steward-CSRF", me.get("csrf").getAsString())
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static void registerAKey(final HttpClient browser, final TestAuthenticator key, final String label)
            throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/webauthn/register/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final HttpResponse<String> finished = finishRegistration(browser, key.register(started.body(), ORIGIN), label);
        assertEquals(200, finished.statusCode(), finished.body());
    }

    /** The finish, with the credential as a string inside the envelope, because only the library may parse it. */
    static HttpResponse<String> finishRegistration(
            final HttpClient browser, final String credential, final String label) throws Exception {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("label", label);
        envelope.addProperty("credential", credential);
        return post(browser, "/auth/webauthn/register/finish", GSON.toJson(envelope));
    }

    static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    /**
     * Drives the whole sign-in the way a browser does, for an account made an admin first.
     *
     * The first sign-in claims the root. SQL makes the others admins, because a grant is limited to three an hour.
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

    static void admitBelowRoot(final DiscordId discordId) throws Exception {
        StandInDiscord.admitBelow(WebFixture.postgres.dataSource(), discordId.value(), "1");
    }

    static String stateFrom(final HttpResponse<String> redirect) {
        assertEquals(302, redirect.statusCode(), redirect.body());
        final String location = redirect.headers().firstValue("Location").orElseThrow();
        final Matcher state = Pattern.compile("[?&]state=([^&]+)").matcher(location);
        assertTrue(state.find(), location);
        return state.group(1);
    }

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
     * The old {@code Data} stays open, because other tests still hold sessions in its pool.
     */
    static void restartTheInterface() throws Exception {
        web.stop();
        web = newWeb();
        webPort = web.start(0).port();
    }

    static HttpResponse<String> logout(final HttpClient browser) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/auth/logout"))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
