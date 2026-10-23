package eu.nordtal.season.steward.auth;

import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;
import eu.nordtal.season.database.JournalDetail;
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

/** Handles the WebAuthn key routes and the second-factor checks that {@code Gatekeeper#guard} runs. */
public final class SecondFactor {

    /** {@code DEFAULT} applies when a request names none. */
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

    private static final int MOST_LABEL = 64;

    private final Function<Context, Sessions.Session> requireSession;
    private final Credentials credentials;
    private final Sessions sessions;
    private final WebAuthn webauthn;
    private final Authenticators authenticators;

    private final Clock clock;

    public SecondFactor(
            final Function<Context, Sessions.Session> requireSession,
            final Credentials credentials,
            final Sessions sessions,
            final WebAuthn webauthn,
            final Authenticators authenticators,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requireSession = requireSession;
        this.credentials = credentials;
        this.sessions = sessions;
        this.webauthn = webauthn;
        this.authenticators = authenticators;
    }

    /** Refuses an account without a factor with a 403 and the code {@code SECOND_FACTOR_MISSING}. */
    void requireAKey(final Sessions.Session who) {
        if (anyFactor(who)) {
            return;
        }
        throw new RequestRefused(403, ANSWER.noKey(), "SECOND_FACTOR_MISSING", false);
    }

    boolean anyFactor(final Sessions.Session who) {
        return credentials.any(who.signedInDiscordId()) || hasApp(who);
    }

    int keysOf(final Sessions.Session who) {
        return credentials.of(who.signedInDiscordId()).size();
    }

    private boolean hasApp(final Sessions.Session who) {
        return authenticators.confirmed(who.signedInDiscordId());
    }

    void requireKeyHeld(final Sessions.Session who) {
        if (who.verified()) {
            return;
        }
        throw required(ANSWER.keyNotHeld());
    }

    void requireKeyRecently(final Sessions.Session who) {
        final Instant until = who.stepUpUntil();
        if (until != null && until.isAfter(clock.instant())) {
            return;
        }
        throw required(ANSWER.keyNotRecent());
    }

    public void requireStepUp(final Context ctx) {
        requireKeyRecently(requireSession.apply(ctx));
    }

    private static RequestRefused required(final MessageRef why) {
        return new RequestRefused(403, why, "SECOND_FACTOR_REQUIRED", true);
    }

    /** An account that has a factor needs a step-up that still holds. */
    public void beginRegistration(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        if (anyFactor(who)) {
            requireKeyRecently(who);
        }
        final WebAuthn.Ceremony ceremony =
                webauthn.startRegistration(who.signedInDiscordId(), who.signedInDisplayName());
        sessions.startCeremony(who.id(), ceremony.parked());
        // forBrowser holds the library's JSON and is sent unchanged.
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

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
        // The step-up may have ended since the begin route.
        final boolean further = anyFactor(who);
        if (further) {
            requireKeyRecently(who);
        }
        final String parked = sessions.consumeCeremony(who.id())
                .orElseThrow(() -> new RequestRefused(400, ANSWER.ceremonyElsewhere(true)));

        final WebAuthn.Registered key;
        try {
            key = webauthn.finishRegistration(parked, answer.credential, label, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            throw new RequestRefused(400, refused.why());
        }
        // The first key counts as held. A further key leaves the step-up unchanged.
        if (!further) {
            sessions.markVerified(who.id(), StepUpLength.DEFAULT.length());
        }
        Journaling.line(ctx, who.signedInDiscordId(), new JournalDetail.KeyRegistered(key.label()));
        ctx.json(new KeyRegistered(key.label(), key.userVerified(), key.backedUp()));
    }

    /** @param backedUp true when the authenticator reports the key as synced */
    public record KeyRegistered(String label, boolean userVerified, boolean backedUp) {}

    public record KeyRenamed(String label) {}

    public record KeyRemoved(String removed, int left) {}

    /** {@code credential} is a {@code String} so that only the WebAuthn library parses it. */
    private static final class Answer {
        private @Nullable String label;
        private @Nullable String credential;
        private @Nullable String hold;
    }

    /** Refuses with a 403 when the account has none. */
    public void beginAssertion(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final WebAuthn.Ceremony ceremony;
        try {
            ceremony = webauthn.startAssertion(who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            throw new RequestRefused(403, ANSWER.noKey(), "SECOND_FACTOR_MISSING", false);
        }
        sessions.startCeremony(who.id(), ceremony.parked());
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

    public void finishAssertion(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final StepUpLength hold = StepUpLength.of(answer.hold);
        final String parked = sessions.consumeCeremony(who.id())
                .orElseThrow(() -> new RequestRefused(400, ANSWER.ceremonyElsewhere(false)));
        final WebAuthn.Held held;
        try {
            held = webauthn.finishAssertion(parked, answer.credential, who.signedInDiscordId());
        } catch (WebAuthn.Refused refused) {
            throw new RequestRefused(400, refused.why());
        }
        sessions.markVerified(who.id(), hold.length());
        Journaling.line(
                ctx,
                who.signedInDiscordId(),
                new JournalDetail.KeyHeld(held.label(), held.userVerified(), hold.length()));
        ctx.json(held);
    }

    public void endStepUp(final Context ctx) {
        sessions.endStepUp(requireSession.apply(ctx).id());
        ctx.status(204);
    }

    private static ByteArray keyIdOf(final Context ctx) {
        try {
            return ByteArray.fromBase64Url(ctx.pathParam("id"));
        } catch (Base64UrlException malformed) {
            throw new BadRequestResponse(
                    "that is not the id of a key - the list in /api/me is" + " where those come from");
        }
    }

    public void renameKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Answer body = ctx.bodyAsClass(Answer.class);
        final String label = body == null || body.label == null ? "" : body.label.trim();
        if (label.isEmpty() || label.length() > MOST_LABEL) {
            throw new RequestRefused(400, ANSWER.keyName(MOST_LABEL));
        }
        if (!credentials.rename(who.signedInDiscordId(), keyIdOf(ctx), label)) {
            throw new RequestRefused(404, ANSWER.noSuchKey());
        }
        Journaling.line(ctx, who.signedInDiscordId(), new JournalDetail.KeyRenamed(label));
        ctx.json(new KeyRenamed(label));
    }

    /** Refuses to remove the last factor. */
    public void removeKey(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final ByteArray id = keyIdOf(ctx);
        final List<Credentials.Key> keys = credentials.of(who.signedInDiscordId());
        final String label = keys.stream()
                .filter(key -> new ByteArray(key.credentialId()).equals(id))
                .map(Credentials.Key::label)
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noSuchKey()));
        if (keys.size() == 1 && !hasApp(who)) {
            throw new RequestRefused(409, ANSWER.lastFactor());
        }
        if (!credentials.remove(who.signedInDiscordId(), id)) {
            throw new RequestRefused(404, ANSWER.noSuchKey());
        }
        final int left = credentials.of(who.signedInDiscordId()).size();
        Journaling.line(ctx, who.signedInDiscordId(), new JournalDetail.KeyRemoved(label, left));
        ctx.json(new KeyRemoved(label, left));
    }
}
