package eu.nordtal.s2.steward.web;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.steward.auth.Credentials;
import eu.nordtal.s2.steward.auth.Sessions;
import eu.nordtal.s2.steward.auth.WebAuthn;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The WebAuthn ceremony, the keys it registers, and the checks {@code Gatekeeper#guard} runs. */
final class SecondFactor {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

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
        throw new RequestRefused(403, ANSWER.noKey(), "SECOND_FACTOR_MISSING", false);
    }

    /** Refuses a session that has not held its key, with the refusal the interface recovers from by holding it. */
    void requireKeyHeld(final Sessions.Session who) {
        if (who.verified()) {
            return;
        }
        throw required(ANSWER.keyNotHeld());
    }

    /**
     * Refuses unless the key was held within the last {@link Web#STEP_UP}, counted from the ceremony.
     *
     * The window does not slide, or anybody working continuously would never be asked.
     */
    void requireKeyRecently(final Sessions.Session who) {
        final Instant held = who.verifiedAt();
        if (held != null && held.isAfter(clock.instant().minus(Web.STEP_UP))) {
            return;
        }
        throw required(ANSWER.keyNotRecent(Web.STEP_UP));
    }

    /**
     * The refusal the interface recovers from: hold the key, then send the same request again.
     *
     * Its code, {@code SECOND_FACTOR_REQUIRED}, tells it apart from the account that has no key at all.
     */
    private static RequestRefused required(final MessageRef why) {
        return new RequestRefused(403, why, "SECOND_FACTOR_REQUIRED", true);
    }

    /**
     * Hands this browser a registration challenge.
     *
     * A further key needs one already held this session, since adding an authenticator is as powerful as having one.
     */
    void beginRegistration(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        if (credentials().any(who.signedInDiscordId()) && !who.verified()) {
            throw new RequestRefused(403, ANSWER.keyFirst());
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
                .orElseThrow(() -> new RequestRefused(400, ANSWER.ceremonyElsewhere(true)));

        final WebAuthn.Registered key;
        try {
            key = webauthn().finishRegistration(parked, answer.credential, label, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            ctx.status(400).json(Map.of("error", refused.getMessage()));
            return;
        }
        // Registering a key is holding it.
        sessions().markVerified(who.id());
        data().audit()
                .record(who.ownLine(JournalAction.REGISTER_KEY, TEXTS.journal().registerKey(key.label())));
        ctx.json(new KeyRegistered(key.label(), key.userVerified(), key.backedUp()));
    }

    /**
     * A security key just registered.
     *
     * @param backedUp whether the authenticator says the key is synced, so losing the device does not lose it
     */
    public record KeyRegistered(String label, boolean userVerified, boolean backedUp) {}

    /** A key's new name. */
    public record KeyRenamed(String label) {}

    /** The name of the key removed, and how many the account still holds. */
    public record KeyRemoved(String removed, int left) {}

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
            throw new RequestRefused(403, ANSWER.noKey(), "SECOND_FACTOR_MISSING", false);
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
                .orElseThrow(() -> new RequestRefused(400, ANSWER.ceremonyElsewhere(false)));
        final WebAuthn.Held held;
        try {
            held = webauthn().finishAssertion(parked, answer.credential, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            ctx.status(400).json(Map.of("error", refused.getMessage()));
            return;
        }
        sessions().markVerified(who.id());
        data().audit()
                .record(who.ownLine(
                        JournalAction.HELD_KEY, TEXTS.journal().heldKey(held.label(), held.userVerified())));
        ctx.json(held);
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
            throw new RequestRefused(404, ANSWER.noSuchKey());
        }
        data().audit()
                .record(who.ownLine(JournalAction.RENAME_KEY, TEXTS.journal().renameKey(label)));
        ctx.json(new KeyRenamed(label));
    }

    /** {@code DELETE /api/keys/{id}}: removes one key, the last one included. */
    void removeKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final ByteArray id = keyIdOf(ctx);
        final String label = credentials().of(who.signedInDiscordId()).stream()
                .filter(key -> new ByteArray(key.credentialId()).equals(id))
                .map(Credentials.Key::label)
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noSuchKey()));
        if (!credentials().remove(who.signedInDiscordId(), id)) {
            throw new RequestRefused(404, ANSWER.noSuchKey());
        }
        final int left = credentials().of(who.signedInDiscordId()).size();
        data().audit()
                .record(who.ownLine(JournalAction.REMOVE_KEY, TEXTS.journal().removeKey(label, left)));
        ctx.json(new KeyRemoved(label, left));
    }
}
