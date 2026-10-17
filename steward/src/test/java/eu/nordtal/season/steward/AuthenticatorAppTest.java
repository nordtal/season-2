package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.steward.auth.TestAuthenticator;
import eu.nordtal.season.steward.auth.Totp;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** An authenticator app as a second factor beside the security key, or instead of it. */
class AuthenticatorAppTest extends WebTestSupport {

    @Test
    void enrolmentCountsOnlyOnceACodeConfirmsIt() throws Exception {
        asAnotherAdmin("770000000000000201", () -> {
            final HttpClient browser = browser();
            signIn(browser);

            final JsonObject started = startEnrolment(browser);
            final byte[] secret = fromBase32(started.get("secret").getAsString());
            assertEquals(20, secret.length);
            assertTrue(started.get("uri").getAsString().startsWith("otpauth://totp/"), started.toString());
            assertFalse(started.getAsJsonObject("qr").get("path").getAsString().isEmpty(), started.toString());
            assertFalse(me(browser).has("authenticator"), "an app counted before a code confirmed it");
            assertEquals(
                    0,
                    count("SELECT count(*) FROM steward_authenticator WHERE position('\\x"
                            + HexFormat.of().formatHex(secret) + "'::bytea in secret) > 0"),
                    "the secret is stored in the clear");

            final HttpResponse<String> wrong = post(browser, "/auth/totp/enrol/finish", codeBody(wrongCode(secret)));
            assertEquals(400, wrong.statusCode(), wrong.body());
            assertFalse(me(browser).has("authenticator"));

            final HttpResponse<String> confirmed =
                    post(browser, "/auth/totp/enrol/finish", codeBody(codeNow(secret, 0)));
            assertEquals(204, confirmed.statusCode(), confirmed.body());

            final JsonObject me = me(browser);
            assertTrue(me.has("authenticator"), me.toString());
            assertTrue(me.get("verified").getAsBoolean(), "the first factor is held as it is added, as a key is");
            assertEquals(
                    1,
                    count("SELECT count(*) FROM journal_entry WHERE action = 'ADD_AUTHENTICATOR'"
                            + " AND actor_id = '770000000000000201'"));
            settleOpenRuns();
            assertEquals(
                    202, post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}").statusCode());
            settleOpenRuns();
        });
    }

    @Test
    void aCodeIsAcceptedOnceAndHoldsTheStepUpAsAsked() throws Exception {
        asAnotherAdmin("770000000000000202", () -> {
            final HttpClient browser = browser();
            signIn(browser);
            final byte[] secret = enrol(browser);
            final String spent = codeNow(secret, 0);

            final HttpClient later = browser();
            signIn(later);
            final HttpResponse<String> replayed = post(later, "/auth/totp/verify", codeBody(spent));
            assertEquals(400, replayed.statusCode(), "the code that confirmed the app was taken again");
            assertFalse(me(later).get("verified").getAsBoolean());

            // The next step's code is within the one step either side the server allows.
            final String next = codeNow(secret, 1);
            final HttpResponse<String> held =
                    post(later, "/auth/totp/verify", "{\"code\":\"" + next + "\",\"hold\":\"ONE_HOUR\"}");
            assertEquals(204, held.statusCode(), held.body());
            assertTrue(me(later).get("verified").getAsBoolean());
            assertEquals(
                    3600,
                    count("SELECT extract(epoch FROM step_up_until - verified_at)::bigint FROM steward_session"
                            + " WHERE discord_id = '770000000000000202' AND step_up_until IS NOT NULL"
                            + " ORDER BY verified_at DESC LIMIT 1"));
            assertEquals(
                    1,
                    count("SELECT count(*) FROM journal_entry WHERE action = 'USED_AUTHENTICATOR'"
                            + " AND actor_id = '770000000000000202'"));

            assertEquals(400, post(later, "/auth/totp/verify", codeBody(next)).statusCode(), "taken twice");
        });
    }

    @Test
    void wrongCodesAreRateLimited() throws Exception {
        asAnotherAdmin("770000000000000203", () -> {
            final HttpClient browser = browser();
            signIn(browser);
            final byte[] secret = enrol(browser);
            final HttpClient later = browser();
            signIn(later);

            for (int wrong = 0; wrong < 5; wrong++) {
                assertEquals(
                        400,
                        post(later, "/auth/totp/verify", codeBody(wrongCode(secret)))
                                .statusCode());
            }
            final HttpResponse<String> limited = post(later, "/auth/totp/verify", codeBody(codeNow(secret, 1)));

            assertEquals(429, limited.statusCode(), "a right code after five wrong ones: " + limited.body());
            assertFalse(me(later).get("verified").getAsBoolean());
        });
    }

    @Test
    void addingAFactorNeedsAStepUpWithAnExistingOne() throws Exception {
        asAnotherAdmin("770000000000000204", () -> {
            final HttpClient browser = browser();
            signIn(browser);
            final TestAuthenticator key = new TestAuthenticator();
            registerAKey(browser, key, "Their key");
            heldLongAgo(browser);

            final HttpResponse<String> refused = post(browser, "/auth/totp/enrol/start", "");
            assertEquals(403, refused.statusCode(), refused.body());
            assertEquals(
                    "SECOND_FACTOR_REQUIRED",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString());

            holdTheKey(browser, key);
            final byte[] secret = enrol(browser);

            // And a key beside the app is a further factor too, asked for with the app's code.
            heldLongAgo(browser);
            assertEquals(403, post(browser, "/auth/webauthn/register/start", "").statusCode());
            assertEquals(
                    204,
                    post(browser, "/auth/totp/verify", codeBody(codeNow(secret, 1)))
                            .statusCode());
            assertEquals(200, post(browser, "/auth/webauthn/register/start", "").statusCode());
        });
    }

    @Test
    void theLastFactorCannotBeRemoved() throws Exception {
        asAnotherAdmin("770000000000000205", () -> {
            final HttpClient browser = browser();
            signIn(browser);
            final byte[] secret = enrol(browser);

            final HttpResponse<String> alone = delete(browser, "/api/authenticator");
            assertEquals(409, alone.statusCode(), "the only factor was removed: " + alone.body());
            assertTrue(me(browser).has("authenticator"));

            final TestAuthenticator key = new TestAuthenticator();
            registerAKey(browser, key, "Their key");
            assertEquals(204, delete(browser, "/api/authenticator").statusCode());
            assertFalse(me(browser).has("authenticator"));
            assertEquals(
                    1,
                    count("SELECT count(*) FROM journal_entry WHERE action = 'REMOVE_AUTHENTICATOR'"
                            + " AND actor_id = '770000000000000205'"));

            final HttpResponse<String> lastKey = delete(browser, "/api/keys/" + key.credentialId());
            assertEquals(409, lastKey.statusCode(), "the only key was removed: " + lastKey.body());
            assertTrue(secret.length > 0);
        });
    }

    /** Signs in as an admin below the root, with no factor yet, and puts the root back afterwards. */
    private static void asAnotherAdmin(final String id, final Body body) throws Exception {
        memberId.set(id);
        memberNick.set("App " + id.substring(id.length() - 3));
        try {
            body.run();
        } finally {
            memberId.set("1");
            memberNick.set("Ally");
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private static JsonObject startEnrolment(final HttpClient browser) throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/totp/enrol/start", "");
        assertEquals(200, started.statusCode(), started.body());
        return GSON.fromJson(started.body(), JsonObject.class);
    }

    /** The whole enrolment, confirmed with this step's code; answers the secret. */
    private static byte[] enrol(final HttpClient browser) throws Exception {
        final byte[] secret = fromBase32(startEnrolment(browser).get("secret").getAsString());
        final HttpResponse<String> confirmed = post(browser, "/auth/totp/enrol/finish", codeBody(codeNow(secret, 0)));
        assertEquals(204, confirmed.statusCode(), confirmed.body());
        return secret;
    }

    private static JsonObject me(final HttpClient browser) throws Exception {
        return GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
    }

    private static String codeBody(final String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    /** This step's code, or one {@code offset} steps on. */
    private static String codeNow(final byte[] secret, final int offset) {
        return Totp.code(secret, Totp.stepAt(Instant.now()) + offset, 6);
    }

    /** A code no step within reach answers, as a guess would be. */
    private static String wrongCode(final byte[] secret) {
        final long now = Totp.stepAt(Instant.now());
        for (int guess = 0; ; guess++) {
            final String code = String.format(Locale.ROOT, "%06d", guess);
            boolean taken = false;
            for (long step = now - 2; step <= now + 3; step++) {
                taken |= Totp.code(secret, step, 6).equals(code);
            }
            if (!taken) {
                return code;
            }
        }
    }

    /** RFC 4648 Base32 back to bytes, written here so the test does not read the secret through the code under test. */
    private static byte[] fromBase32(final String text) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int at = 0; at < text.length(); at++) {
            buffer = (buffer << 5) | alphabet.indexOf(text.charAt(at));
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
