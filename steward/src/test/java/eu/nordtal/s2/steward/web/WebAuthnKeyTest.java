package eu.nordtal.s2.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.auth.TestAuthenticator;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Registering and holding a security key, and the write gate that needs one held recently. */
class WebAuthnKeyTest extends WebTestSupport {

    @Test
    void withoutAKeyThereIsNowhereToGo() throws Exception {
        memberId.set("770000000000000001");
        memberNick.set("Newcomer");
        try {
            final HttpClient browser = browser();
            signIn(browser);

            // The sign-in worked. This is not a refused Discord login and must not look like one.
            final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
            assertTrue(me.get("signedIn").getAsBoolean(), me.toString());
            assertEquals(0, me.getAsJsonArray("keys").size(), me.toString());
            assertFalse(me.get("verified").getAsBoolean(), me.toString());

            // 403 rather than 401: 401 becomes the sign-in page, and this person already came from Discord.
            final HttpResponse<String> refused = get(browser, "/api/services");
            assertEquals(403, refused.statusCode(), refused.body());
            assertEquals(
                    "SECOND_FACTOR_MISSING",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString(),
                    "the interface cannot tell this apart from an ordinary refusal: " + refused.body());

            // Including the ones that write: a missing key is not a read-only mode.
            assertEquals(
                    403, post(browser, "/api/updates", "{\"kind\":\"UPDATE\"}").statusCode());
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @Test
    void aKeyIsRegisteredAndThenEverythingWorks() throws Exception {
        memberId.set("770000000000000002");
        memberNick.set("Registrant");
        try {
            final HttpClient browser = browser();
            signIn(browser);
            assertEquals(403, get(browser, "/api/services").statusCode(), "the gate was open");

            registerAKey(browser, new TestAuthenticator(), "YubiKey blau");

            assertEquals(
                    200, get(browser, "/api/services").statusCode(), "the key was registered and the gate stayed shut");

            final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
            assertEquals(1, me.getAsJsonArray("keys").size(), me.toString());
            assertEquals(
                    "YubiKey blau",
                    me.getAsJsonArray("keys")
                            .get(0)
                            .getAsJsonObject()
                            .get("label")
                            .getAsString());
            // Registering a key is holding it, so the session is verified without a second ceremony.
            assertTrue(me.get("verified").getAsBoolean(), me.toString());

            assertEquals(
                    "YubiKey blau",
                    journalledBy("770000000000000002", "REGISTER_KEY")
                            .getAsJsonObject("facts")
                            .get("label")
                            .getAsString());
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @Test
    void aChallengeIsAnsweredExactlyOnce() throws Exception {
        memberId.set("770000000000000003");
        memberNick.set("Replayer");
        try {
            final HttpClient browser = browser();
            signIn(browser);
            final TestAuthenticator key = new TestAuthenticator();

            final HttpResponse<String> started = post(browser, "/auth/webauthn/register/start", "");
            assertEquals(200, started.statusCode(), started.body());

            assertEquals(
                    200,
                    finishRegistration(browser, key.register(started.body(), ORIGIN), "Once")
                            .statusCode());

            // A different authenticator answering the same challenge: the library refuses a re-registered credential.
            final HttpResponse<String> again =
                    finishRegistration(browser, new TestAuthenticator().register(started.body(), ORIGIN), "Twice");
            assertEquals(400, again.statusCode(), again.body());
            assertEquals(
                    1,
                    GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                            .getAsJsonArray("keys")
                            .size(),
                    "a second authenticator answered a challenge that had already been used");
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @Test
    void anotherOriginIsRefused() throws Exception {
        memberId.set("770000000000000004");
        memberNick.set("Elsewhere");
        try {
            final HttpClient browser = browser();
            signIn(browser);

            final HttpResponse<String> started = post(browser, "/auth/webauthn/register/start", "");
            // A subdomain of the relying party, not a stranger's domain, is the case the id actually creates.
            final String answer = new TestAuthenticator().register(started.body(), "https://bluemap.nordtal.eu");

            final HttpResponse<String> refused = finishRegistration(browser, answer, "From next door");
            assertEquals(400, refused.statusCode(), refused.body());
            assertEquals(
                    0,
                    GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                            .getAsJsonArray("keys")
                            .size(),
                    "a foreign origin registered a key");
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @Test
    void aChallengeNobodyIssuedIsRefused() throws Exception {
        memberId.set("770000000000000005");
        memberNick.set("Inventor");
        try {
            final HttpClient browser = browser();
            signIn(browser);

            final HttpResponse<String> started = post(browser, "/auth/webauthn/register/start", "");
            final String answer = new TestAuthenticator()
                    .register(
                            started.body(),
                            ORIGIN,
                            // 32 bytes of somebody else's choosing; the shape is right but the value was never issued.
                            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");

            final HttpResponse<String> refused = finishRegistration(browser, answer, "Invented");
            assertEquals(400, refused.statusCode(), refused.body());
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @Test
    void aSecondKeyNeedsTheFirst() throws Exception {
        // A second session of an account that already has a key: it gets past the gate but may not register one.
        final HttpClient stolen = browser();
        signIn(stolen);
        assertFalse(
                GSON.fromJson(get(stolen, "/api/me").body(), JsonObject.class)
                        .get("verified")
                        .getAsBoolean(),
                "a fresh session started out verified");

        final HttpResponse<String> refused = post(stolen, "/auth/webauthn/register/start", "");
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("already has a key"), refused.body());
    }

    @Test
    void discordAloneIsNotEnough() throws Exception {
        // The same account as everywhere else in this class, so it has a key: a cookie past the redirect only.
        final HttpClient fresh = browser();
        signIn(fresh);

        final JsonObject me = GSON.fromJson(get(fresh, "/api/me").body(), JsonObject.class);
        assertTrue(me.get("signedIn").getAsBoolean(), me.toString());
        assertFalse(me.get("verified").getAsBoolean(), "a fresh session started out verified");
        assertFalse(me.getAsJsonArray("keys").isEmpty(), "this account should have a key already");

        // Reading is refused too.
        for (final String path :
                new String[] {"/api/services", "/api/people", "/api/journal", "/api/updates", "/api/setting-groups"}) {
            final HttpResponse<String> refused = get(fresh, path);
            assertEquals(403, refused.statusCode(), path + " answered " + refused.body());
            assertEquals(
                    "SECOND_FACTOR_REQUIRED",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString(),
                    path + " answered " + refused.body());
        }

        holdTheKey(fresh, authenticator);

        assertEquals(200, get(fresh, "/api/people").statusCode());
        assertTrue(
                GSON.fromJson(get(fresh, "/api/me").body(), JsonObject.class)
                        .get("verified")
                        .getAsBoolean(),
                "the key was held and the session is not verified");
    }

    @Test
    void aChallengeIsSpentWhenItIsAnswered() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final String answer = authenticator.assertion(started.body(), ORIGIN);

        assertEquals(200, finishAssertion(browser, answer).statusCode());
        // The same bytes again; the column was emptied in the same statement that read it, so nothing matches.
        final HttpResponse<String> replayed = finishAssertion(browser, answer);
        assertEquals(400, replayed.statusCode(), replayed.body());
        assertTrue(
                replayed.body().contains("already finished") || replayed.body().contains("not started"),
                replayed.body());
    }

    @Test
    void anotherKeyIsNotThisAccountsKey() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());

        // A key this service has never seen, answering a challenge it really was issued: only the lookup matters.
        final HttpResponse<String> refused =
                finishAssertion(browser, new TestAuthenticator().assertion(started.body(), ORIGIN));
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(
                GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                        .get("verified")
                        .getAsBoolean(),
                "a stranger's key verified this session");
    }

    @Test
    void theOriginIsCheckedOnASignInToo() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started = post(browser, "/auth/webauthn/authenticate/start", "");
        final HttpResponse<String> refused =
                finishAssertion(browser, authenticator.assertion(started.body(), "https://bluemap.nordtal.eu"));

        // The relying party id spans nordtal.eu, but the ORIGIN check is narrower, to this one address.
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(
                GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                        .get("verified")
                        .getAsBoolean(),
                "a ceremony from another subdomain verified");
    }

    @Test
    void theWindowClosesOnWritingAndNotOnReading() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        settleOpenRuns();
        assertEquals(202, post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}").statusCode());
        settleOpenRuns();

        heldLongAgo(browser);

        // Reading is KEY_HELD: the key was held in this session, and that does not expire.
        assertEquals(200, get(browser, "/api/services").statusCode());

        final HttpResponse<String> refused = post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}");
        assertEquals(403, refused.statusCode(), refused.body());
        final JsonObject body = GSON.fromJson(refused.body(), JsonObject.class);
        assertEquals("SECOND_FACTOR_REQUIRED", body.get("code").getAsString(), refused.body());
        // `retryable` tells the interface to open the dialog and retry, rather than draw a red box.
        assertTrue(body.get("retryable").getAsBoolean(), refused.body());

        // Holding it again lets the same request through: "one tap, not two" as the server sees it.
        holdTheKey(browser, authenticator);
        assertEquals(202, post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}").statusCode());
        settleOpenRuns();
    }

    @Test
    void everyWriteAsksAgain() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        heldLongAgo(browser);

        // Four writes.
        final String[][] writes = {
            {"/api/updates", "{\"kind\":\"UPDATE\"}"},
            {"/api/access/grant", "{\"discordId\":\"1\",\"days\":1}"},
            {"/api/season/phase", "{\"phase\":\"LIVE\"}"},
            {"/api/smp/milestone", "{\"key\":\"aufbruch\"}"},
        };
        for (final String[] write : writes) {
            final HttpResponse<String> refused = post(browser, write[0], write[1]);
            assertEquals(403, refused.statusCode(), write[0] + " answered " + refused.body());
            assertEquals(
                    "SECOND_FACTOR_REQUIRED",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString(),
                    write[0] + " answered " + refused.body());
        }
    }

    @Test
    void aSecondKeyIsOrdinaryWork() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);

        final TestAuthenticator second = new TestAuthenticator();
        registerAKey(browser, second, "My phone");

        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        final JsonObject added = me.getAsJsonArray("keys").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(key -> "My phone".equals(key.get("label").getAsString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(me.toString()));
        final String id = added.get("id").getAsString();

        assertEquals(
                200,
                put(browser, "/api/keys/" + id, "{\"label\":\"My old phone\"}").statusCode());
        assertTrue(get(browser, "/api/me").body().contains("My old phone"));

        assertEquals(200, delete(browser, "/api/keys/" + id).statusCode());
        assertFalse(get(browser, "/api/me").body().contains("My old phone"));
        // The first key is untouched, so the account still works afterwards.
        assertEquals(200, get(browser, "/api/services").statusCode());
    }

    @Test
    void aKeyIsRemovedOnlyFromTheAccountItIsOn() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        // A credential id is handed to every browser that starts a sign-in, so a stranger can hold it too.
        final HttpResponse<String> refused = delete(browser, "/api/keys/" + new TestAuthenticator().credentialId());
        assertEquals(404, refused.statusCode(), refused.body());
    }

    @Test
    void addingAKeyIsAsPowerfulAsHavingOne() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        heldLongAgo(browser);
        // A stolen session that could add an authenticator would have made itself permanent.
        assertEquals(
                403,
                delete(browser, "/api/keys/" + authenticator.credentialId()).statusCode());
        assertEquals(
                403,
                put(browser, "/api/keys/" + authenticator.credentialId(), "{\"label\":\"mine now\"}")
                        .statusCode());
    }
}
