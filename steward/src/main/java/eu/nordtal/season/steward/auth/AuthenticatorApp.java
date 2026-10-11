package eu.nordtal.season.steward.auth;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.journal.Unjournaled;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.nayuki.qrcodegen.QrCode;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The authenticator app's routes: enrolling one, stepping up with its code, and removing it.
 *
 * Adding a factor beside another asks for a step-up with that one, as a further security key does.
 */
public final class AuthenticatorApp {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** The issuer an app files the account under; the account is the admin's name. */
    private static final String ISSUER = "Nordtal Steward";

    private final Function<Context, Sessions.Session> requireSession;
    private final SecondFactor secondFactor;
    private final @Nullable Authenticators authenticators;
    private final @Nullable Sessions sessions;

    public AuthenticatorApp(
            final Function<Context, Sessions.Session> requireSession,
            final SecondFactor secondFactor,
            final @Nullable Authenticators authenticators,
            final @Nullable Sessions sessions) {
        this.requireSession = requireSession;
        this.secondFactor = secondFactor;
        this.authenticators = authenticators;
        this.sessions = sessions;
    }

    /** Its routes: doors of their own, as the key's ceremonies are, and a removal gated like a key's. */
    public void register(final JavalinConfig cfg) {
        cfg.routes.post("/auth/totp/enrol/start", this::startEnrolment, Gate.SIGNED_IN, Unjournaled.CEREMONY_START);
        cfg.routes.post(
                "/auth/totp/enrol/finish",
                this::finishEnrolment,
                Gate.SIGNED_IN,
                Journaled.as(JournalAction.ADD_AUTHENTICATOR));
        cfg.routes.post(
                "/auth/totp/verify", this::verify, Gate.SIGNED_IN, Journaled.as(JournalAction.USED_AUTHENTICATOR));
        cfg.routes.delete(
                "/api/authenticator", this::remove, Gate.KEY_FRESH, Journaled.as(JournalAction.REMOVE_AUTHENTICATOR));
    }

    private Authenticators authenticators() {
        final Authenticators found =
                Objects.requireNonNull(authenticators, "no database - this route is not available without one");
        if (!found.offered()) {
            throw new RequestRefused(404, ANSWER.authenticatorNotOffered());
        }
        return found;
    }

    private Sessions sessions() {
        return Objects.requireNonNull(sessions, "no database - this route is not available without one");
    }

    /** What the enrolment dialog draws: the secret to type, the URI to scan, and that URI as a QR code. */
    public record Enrolment(String secret, String uri, Qr qr) {}

    /** A QR code as one SVG path of unit squares, {@code size} modules a side, without its quiet zone. */
    public record Qr(int size, String path) {}

    /** The typed code and, for a step-up, how long it holds. */
    private static final class Typed {
        private @Nullable String code;
        private @Nullable String hold;
    }

    /** {@code POST /auth/totp/enrol/start}: a fresh secret, which counts for nothing until a code confirms it. */
    public void startEnrolment(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Authenticators apps = authenticators();
        if (secondFactor.anyFactor(who)) {
            secondFactor.requireKeyRecently(who);
        }
        final byte[] secret = apps.enrol(who.signedInDiscordId())
                .orElseThrow(() -> new RequestRefused(409, ANSWER.authenticatorThere()));
        final String uri = Totp.uri(ISSUER, who.signedInDisplayName(), secret);
        ctx.json(new Enrolment(Totp.base32(secret), uri, qrOf(uri)));
    }

    /** {@code POST /auth/totp/enrol/finish}: one code the app shows makes it a factor, under the rule of the start. */
    public void finishEnrolment(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Authenticators apps = authenticators();
        final String code = codeOf(ctx).code();
        // Checked again here, since the step-up may have ended between the two routes.
        final boolean further = secondFactor.anyFactor(who);
        if (further) {
            secondFactor.requireKeyRecently(who);
        }
        accept(apps.check(who.signedInDiscordId(), code, true));
        // The first factor added is held, as the first key is; a further one leaves the step-up as it was.
        if (!further) {
            sessions().markVerified(who.id(), SecondFactor.StepUpLength.DEFAULT.length());
        }
        Journaling.line(ctx, who.signedInDiscordId(), TEXTS.journal().addAuthenticator());
        ctx.status(204);
    }

    /** {@code POST /auth/totp/verify}: the app's code instead of the key, at the door and for a step-up alike. */
    public void verify(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Authenticators apps = authenticators();
        final CodeAndHold typed = codeOf(ctx);
        accept(apps.check(who.signedInDiscordId(), typed.code(), false));
        sessions().markVerified(who.id(), typed.hold().length());
        Journaling.line(
                ctx,
                who.signedInDiscordId(),
                TEXTS.journal().usedAuthenticator(typed.hold().length()));
        ctx.status(204);
    }

    /** {@code DELETE /api/authenticator}: removes the app, unless it is the account's last factor. */
    public void remove(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final int keys = secondFactor.keysOf(who);
        if (keys == 0) {
            throw new RequestRefused(409, ANSWER.lastFactor());
        }
        if (!Objects.requireNonNull(authenticators, "no database").remove(who.signedInDiscordId())) {
            throw new RequestRefused(404, ANSWER.noAuthenticator());
        }
        Journaling.line(ctx, who.signedInDiscordId(), TEXTS.journal().removeAuthenticator(keys));
        ctx.status(204);
    }

    private record CodeAndHold(String code, SecondFactor.StepUpLength hold) {}

    private static CodeAndHold codeOf(final Context ctx) {
        final Typed typed = ctx.bodyAsClass(Typed.class);
        if (typed == null || typed.code == null || typed.code.isBlank()) {
            throw new BadRequestResponse("no code in that request");
        }
        return new CodeAndHold(typed.code, SecondFactor.StepUpLength.of(typed.hold));
    }

    /** Turns anything but an accepted code into the refusal the dialog shows under the field. */
    private static void accept(final Authenticators.Checked checked) {
        switch (checked.outcome()) {
            case ACCEPTED -> {}
            case WRONG -> throw new RequestRefused(400, ANSWER.codeWrong());
            case TAKEN -> throw new RequestRefused(400, ANSWER.codeTaken());
            case LOCKED ->
                throw new RequestRefused(
                        429, ANSWER.tooManyCodes(Objects.requireNonNull(checked.lockedUntil(), "a lock has an end")));
            case NONE -> throw new RequestRefused(404, ANSWER.noAuthenticator());
        }
    }

    /** The URI as a QR code at medium error correction, one {@code h1v1h-1z} square per dark module. */
    static Qr qrOf(final String uri) {
        final QrCode qr = QrCode.encodeText(uri, QrCode.Ecc.MEDIUM);
        final StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
                }
            }
        }
        return new Qr(qr.size, path.toString());
    }
}
