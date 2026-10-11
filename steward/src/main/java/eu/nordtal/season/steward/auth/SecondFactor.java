package eu.nordtal.season.steward.auth;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The WebAuthn ceremony, the keys it registers, and the checks {@code Gatekeeper#guard} runs. */
public final class SecondFactor {

    /**
     * How long one step-up holds, chosen on the step-up sheet for that step-up only.
     *
     * The server offers these and no other; the first is the default, and none is configurable.
     */
    public enum StepUpLength {
        QUARTER_HOUR(Duration.ofMinutes(15)),
        ONE_HOUR(Duration.ofHours(1)),
        SIX_HOURS(Duration.ofHours(6));

        public static final StepUpLength DEFAULT = QUARTER_HOUR;

        private final Duration length;

        StepUpLength(final Duration length) {
            this.length = length;
        }

        public Duration length() {
            return length;
        }

        /** The hold a finish route asked for: none is the default, and one the server does not offer is refused. */
        static StepUpLength of(final @Nullable String asked) {
            if (asked == null) {
                return DEFAULT;
            }
            for (final StepUpLength hold : values()) {
                if (hold.name().equals(asked)) {
                    return hold;
                }
            }
            throw new BadRequestResponse("a step-up holds for one of " + Arrays.toString(values()));
        }
    }

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** The longest name a key takes, so that it can be told apart from the next one. */
    private static final int MOST_LABEL = 64;

    private final Function<Context, Sessions.Session> requireSession;
    private final @Nullable Credentials credentials;
    private final @Nullable Sessions sessions;
    private final @Nullable WebAuthn webauthn;
    private final @Nullable Authenticators authenticators;

    private final Clock clock;

    public SecondFactor(
            final Function<Context, Sessions.Session> requireSession,
            final @Nullable Credentials credentials,
            final @Nullable Sessions sessions,
            final @Nullable WebAuthn webauthn,
            final @Nullable Authenticators authenticators,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requireSession = requireSession;
        this.credentials = credentials;
        this.sessions = sessions;
        this.webauthn = webauthn;
        this.authenticators = authenticators;
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

    /** Refuses an account with neither a key nor an app, which reaches only {@code /api/me}, with a 403 and a code. */
    void requireAKey(final Sessions.Session who) {
        if (credentials == null || anyFactor(who)) {
            return;
        }
        throw new RequestRefused(403, ANSWER.noKey(), "SECOND_FACTOR_MISSING", false);
    }

    /** Whether this account has a second factor, a security key or a confirmed authenticator app. */
    boolean anyFactor(final Sessions.Session who) {
        return credentials().any(who.signedInDiscordId()) || hasApp(who);
    }

    /** How many security keys this account has. */
    int keysOf(final Sessions.Session who) {
        return credentials().of(who.signedInDiscordId()).size();
    }

    private boolean hasApp(final Sessions.Session who) {
        return authenticators != null && authenticators.confirmed(who.signedInDiscordId());
    }

    /** Refuses a session that has not held its key, with the refusal the interface recovers from by holding it. */
    void requireKeyHeld(final Sessions.Session who) {
        if (who.verified()) {
            return;
        }
        throw required(ANSWER.keyNotHeld());
    }

    /**
     * Refuses unless this session's step-up still holds, until the end its ceremony set.
     *
     * The window does not slide, or anybody working continuously would never be asked.
     */
    void requireKeyRecently(final Sessions.Session who) {
        final Instant until = who.stepUpUntil();
        if (until != null && until.isAfter(clock.instant())) {
            return;
        }
        throw required(ANSWER.keyNotRecent());
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
     * A further factor needs a step-up that still holds: adding one is as powerful as having one.
     */
    public void beginRegistration(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        if (anyFactor(who)) {
            requireKeyRecently(who);
        }
        final WebAuthn.Ceremony ceremony =
                webauthn().startRegistration(who.signedInDiscordId(), who.signedInDisplayName());
        sessions().startCeremony(who.id(), ceremony.parked());
        // The library's own JSON, straight through: see WebAuthn on the boundary.
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

    /**
     * Takes the browser's answer, verifies it and writes the key down, under the same rule as the start.
     *
     * The credential is a {@code String} field, since only the library may parse it and Gson must never see it.
     */
    public void finishRegistration(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final String label = answer.label == null ? "" : answer.label.trim();
        if (label.isEmpty() || label.length() > MOST_LABEL) {
            throw new RequestRefused(400, ANSWER.keyName(MOST_LABEL));
        }
        // Checked again here, since the window may have closed between the two routes.
        final boolean further = anyFactor(who);
        if (further) {
            requireKeyRecently(who);
        }
        final String parked = sessions()
                .consumeCeremony(who.id())
                .orElseThrow(() -> new RequestRefused(400, ANSWER.ceremonyElsewhere(true)));

        final WebAuthn.Registered key;
        try {
            key = webauthn().finishRegistration(parked, answer.credential, label, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            throw new RequestRefused(400, refused.why());
        }
        // Registering the first key is holding it; a further one leaves the window where the held key set it.
        if (!further) {
            sessions().markVerified(who.id(), StepUpLength.DEFAULT.length());
        }
        Journaling.line(ctx, who.signedInDiscordId(), TEXTS.journal().registerKey(key.label()));
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

    /** The body of the WebAuthn finish routes: {@code credential} stays a string, {@code hold} names a length. */
    private static final class Answer {
        private @Nullable String label;
        private @Nullable String credential;
        private @Nullable String hold;
    }

    /** Hands this browser a challenge for a key it already has, or refuses if it has none. */
    public void beginAssertion(final Context ctx) {
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
    public void finishAssertion(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final StepUpLength hold = StepUpLength.of(answer.hold);
        final String parked = sessions()
                .consumeCeremony(who.id())
                .orElseThrow(() -> new RequestRefused(400, ANSWER.ceremonyElsewhere(false)));
        final WebAuthn.Held held;
        try {
            held = webauthn().finishAssertion(parked, answer.credential, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            throw new RequestRefused(400, refused.why());
        }
        sessions().markVerified(who.id(), hold.length());
        Journaling.line(
                ctx,
                who.signedInDiscordId(),
                TEXTS.journal().heldKeyFor(held.label(), held.userVerified(), hold.length()));
        ctx.json(held);
    }

    /** {@code DELETE /api/step-up}: ends this session's step-up now; reading goes on, the next change asks again. */
    public void endStepUp(final Context ctx) {
        sessions().endStepUp(requireSession.apply(ctx).id());
        ctx.status(204);
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
    public void renameKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer body = ctx.bodyAsClass(Answer.class);
        final String label = body == null || body.label == null ? "" : body.label.trim();
        if (label.isEmpty() || label.length() > MOST_LABEL) {
            throw new RequestRefused(400, ANSWER.keyName(MOST_LABEL));
        }
        if (!credentials().rename(who.signedInDiscordId(), keyIdOf(ctx), label)) {
            throw new RequestRefused(404, ANSWER.noSuchKey());
        }
        Journaling.line(ctx, who.signedInDiscordId(), TEXTS.journal().renameKey(label));
        ctx.json(new KeyRenamed(label));
    }

    /** {@code DELETE /api/keys/{id}}: removes one key, unless it is the account's last factor. */
    public void removeKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final ByteArray id = keyIdOf(ctx);
        final List<Credentials.Key> keys = credentials().of(who.signedInDiscordId());
        final String label = keys.stream()
                .filter(key -> new ByteArray(key.credentialId()).equals(id))
                .map(Credentials.Key::label)
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noSuchKey()));
        if (keys.size() == 1 && !hasApp(who)) {
            throw new RequestRefused(409, ANSWER.lastFactor());
        }
        if (!credentials().remove(who.signedInDiscordId(), id)) {
            throw new RequestRefused(404, ANSWER.noSuchKey());
        }
        final int left = credentials().of(who.signedInDiscordId()).size();
        Journaling.line(ctx, who.signedInDiscordId(), TEXTS.journal().removeKey(label, left));
        ctx.json(new KeyRemoved(label, left));
    }
}
