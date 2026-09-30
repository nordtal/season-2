package eu.nordtal.s2.steward.ui;

import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;
import eu.nordtal.s2.steward.ui.auth.Credentials;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.auth.WebAuthn;
import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The WebAuthn ceremony, the keys it registers, and the checks {@code Gatekeeper#guard} runs. */
final class SecondFactor {

    private final Function<Context, Sessions.Session> requireSession;
    private final @Nullable Data data;
    private final @Nullable Credentials credentials;
    private final @Nullable Sessions sessions;
    private final @Nullable WebAuthn webauthn;

    private final Clock clock;

    SecondFactor(
            final Function<Context, Sessions.Session> requireSession,
            final @Nullable Data data,
            final @Nullable Credentials credentials,
            final @Nullable Sessions sessions,
            final @Nullable WebAuthn webauthn,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.requireSession = requireSession;
        this.data = data;
        this.credentials = credentials;
        this.sessions = sessions;
        this.webauthn = webauthn;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    private Credentials credentials() {
        return Objects.requireNonNull(credentials, "no database - this route is not available without one");
    }

    private Sessions sessions() {
        return Objects.requireNonNull(sessions, "no database - this route is not available without one");
    }

    private WebAuthn webauthn() {
        return Objects.requireNonNull(webauthn, "no database - this route is not available without one");
    }

    /** Refuses an account with no registered key, which reaches only {@code /api/me}, with a 403 and a code. */
    void requireAKey(final Sessions.Session who) {
        if (credentials == null || credentials().any(who.signedInDiscordId())) {
            return;
        }
        throw new SecondFactorMissing();
    }

    /** Refuses a session that has not held its key, with the refusal the interface recovers from by holding it. */
    void requireKeyHeld(final Sessions.Session who) {
        if (who.verified()) {
            return;
        }
        throw new SecondFactorRequired("This sign-in has not used its security key yet.");
    }

    /**
     * Refuses unless the key was held within the last {@link StewardUi#STEP_UP}, counted from the ceremony.
     *
     * The window does not slide, or anybody working continuously would never be asked.
     */
    void requireKeyRecently(final Sessions.Session who) {
        final Instant held = who.verifiedAt();
        if (held != null && held.isAfter(clock.instant().minus(StewardUi.STEP_UP))) {
            return;
        }
        throw new SecondFactorRequired("This is one of the things Steward asks for the key before"
                + " doing, and it has not been held in the last "
                + StewardUi.STEP_UP.toMinutes() + " minutes.");
    }

    /**
     * The refusal the interface recovers from: hold the key, then send the same request again.
     *
     * Its code, {@code SECOND_FACTOR_REQUIRED}, tells it apart from {@link SecondFactorMissing}.
     */
    static final class SecondFactorRequired extends RuntimeException {

        SecondFactorRequired(final String message) {
            super(message);
        }
    }

    /** The account has no key yet; the interface sends it to the setup page. */
    static final class SecondFactorMissing extends RuntimeException {

        SecondFactorMissing() {
            super("This account has no security key yet, and Steward cannot be used without one."
                    + " Register a key and this request will work.");
        }
    }

    /**
     * Hands this browser a registration challenge.
     *
     * A further key needs one already held this session, since adding an authenticator is as powerful as having one.
     */
    void beginRegistration(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        if (credentials().any(who.signedInDiscordId()) && !who.verified()) {
            throw new ForbiddenResponse("This account already has a key, so adding another one"
                    + " needs the key you already have. Sign in again and use it first.");
        }
        final WebAuthn.Ceremony ceremony =
                webauthn().startRegistration(who.signedInDiscordId(), who.signedInDisplayName());
        sessions().startCeremony(who.id(), ceremony.parked());
        // The library's own JSON, straight through: see WebAuthn on the boundary.
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

    /**
     * Takes the browser's answer, verifies it and writes the key down.
     *
     * The credential is a {@code String} field, since only the library may parse it and Gson must never see it.
     */
    void finishRegistration(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final String label = answer.label == null ? "" : answer.label.trim();
        if (label.isEmpty() || label.length() > 64) {
            throw new BadRequestResponse(
                    "a key needs a name of 1 to 64 characters, so that it can" + " be told apart from the next one");
        }
        final String parked = sessions()
                .consumeCeremony(who.id())
                .orElseThrow(() -> new BadRequestResponse("that registration was not started in this browser,"
                        + " or it was already finished, or it sat unanswered for ten minutes -"
                        + " start it again"));

        final WebAuthn.Registered key;
        try {
            key = webauthn().finishRegistration(parked, answer.credential, label, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            ctx.status(400).json(Map.of("error", refused.getMessage()));
            return;
        }
        // Registering a key is holding it.
        sessions().markVerified(who.id());
        // audit_log.actor is varchar(32), so the actor is the Discord id.
        data().audit()
                .record(
                        "REGISTER_KEY",
                        who.signedInDiscordId().value(),
                        who.signedInDiscordId().value(),
                        null,
                        "registered the security key \"" + key.label() + "\"");
        ctx.json(Map.of("label", key.label(), "userVerified", key.userVerified(), "backedUp", key.backedUp()));
    }

    /** The body of the WebAuthn finish routes; {@code credential} stays a string. */
    private static final class Answer {
        private @Nullable String label;
        private @Nullable String credential;
    }

    /** Hands this browser a challenge for a key it already has, or refuses if it has none. */
    void beginAssertion(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final WebAuthn.Ceremony ceremony;
        try {
            ceremony = webauthn().startAssertion(who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            throw new SecondFactorMissing();
        }
        sessions().startCeremony(who.id(), ceremony.parked());
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

    /**
     * Takes the answer, verifies it, and stamps this session as one that has held its key.
     *
     * The stamp is a column, so a restart of this container is never a way to be asked less.
     */
    void finishAssertion(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final String parked = sessions()
                .consumeCeremony(who.id())
                .orElseThrow(() -> new BadRequestResponse("that sign-in was not started in this browser, or it"
                        + " was already finished, or it sat unanswered for ten minutes - start it"
                        + " again"));
        final WebAuthn.Held held;
        try {
            held = webauthn().finishAssertion(parked, answer.credential, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            ctx.status(400).json(Map.of("error", refused.getMessage()));
            return;
        }
        sessions().markVerified(who.id());
        data().audit()
                .record(
                        "HELD_KEY",
                        who.signedInDiscordId().value(),
                        who.signedInDiscordId().value(),
                        null,
                        "held the security key \"" + held.label() + "\""
                                + (held.userVerified() ? " and unlocked it" : ""));
        ctx.json(Map.of("label", held.label(), "userVerified", held.userVerified()));
    }

    /** The credential id out of the path, base64url as {@code /api/me} lists it. */
    private static ByteArray keyIdOf(final Context ctx) {
        try {
            return ByteArray.fromBase64Url(ctx.pathParam("id"));
        } catch (Base64UrlException malformed) {
            throw new BadRequestResponse(
                    "that is not the id of a key - the list in /api/me is" + " where those come from");
        }
    }

    /** {@code PUT /api/keys/{id}}: what this key is called, so two can be told apart. */
    void renameKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer body = ctx.bodyAsClass(Answer.class);
        final String label = body == null || body.label == null ? "" : body.label.trim();
        if (label.isEmpty() || label.length() > 64) {
            throw new BadRequestResponse(
                    "a key needs a name of 1 to 64 characters, so that it can" + " be told apart from the next one");
        }
        if (!credentials().rename(who.signedInDiscordId(), keyIdOf(ctx), label)) {
            throw new NotFoundResponse("this account has no key of that id");
        }
        data().audit()
                .record(
                        "RENAME_KEY",
                        who.signedInDiscordId().value(),
                        who.signedInDiscordId().value(),
                        null,
                        "renamed a security key to \"" + label + "\"");
        ctx.json(Map.of("label", label));
    }

    /** {@code DELETE /api/keys/{id}}: removes one key, the last one included. */
    void removeKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final ByteArray id = keyIdOf(ctx);
        final String label = credentials().of(who.signedInDiscordId()).stream()
                .filter(key -> new ByteArray(key.credentialId()).equals(id))
                .map(Credentials.Key::label)
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse("this account has no key of that id"));
        if (!credentials().remove(who.signedInDiscordId(), id)) {
            throw new NotFoundResponse("this account has no key of that id");
        }
        final int left = credentials().of(who.signedInDiscordId()).size();
        data().audit()
                .record(
                        "REMOVE_KEY",
                        who.signedInDiscordId().value(),
                        who.signedInDiscordId().value(),
                        null,
                        "removed the security key \"" + label + "\" - " + left + " left on this account");
        ctx.json(Map.of("removed", label, "left", left));
    }
}
