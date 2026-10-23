package eu.nordtal.season.steward.auth;

import eu.nordtal.season.database.JournalDetail;
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

/** Routes for enrolling an authenticator app, stepping up with its code and removing it. */
public final class AuthenticatorApp {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final String ISSUER = "Nordtal Steward";

    private final Function<Context, Sessions.Session> requireSession;
    private final SecondFactor secondFactor;
    private final Authenticators authenticators;
    private final Sessions sessions;

    public AuthenticatorApp(
            final Function<Context, Sessions.Session> requireSession,
            final SecondFactor secondFactor,
            final Authenticators authenticators,
            final Sessions sessions) {
        this.requireSession = requireSession;
        this.secondFactor = secondFactor;
        this.authenticators = authenticators;
        this.sessions = sessions;
    }

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
        if (!authenticators.offered()) {
            throw new RequestRefused(404, ANSWER.authenticatorNotOffered());
        }
        return authenticators;
    }

    public record Enrolment(String secret, String uri, Qr qr) {}

    /** Holds a QR code as an SVG path of unit squares, {@code size} modules per side, without a quiet zone. */
    public record Qr(int size, String path) {}

    private static final class Typed {
        private @Nullable String code;
        private @Nullable String hold;
    }

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

    public void finishEnrolment(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Authenticators apps = authenticators();
        final String code = codeOf(ctx).code();
        // The step-up may have ended since the start route.
        final boolean further = secondFactor.anyFactor(who);
        if (further) {
            secondFactor.requireKeyRecently(who);
        }
        accept(apps.check(who.signedInDiscordId(), code, true));
        // The first factor counts as held. A further factor leaves the step-up unchanged.
        if (!further) {
            sessions.markVerified(who.id(), SecondFactor.StepUpLength.DEFAULT.length());
        }
        Journaling.line(ctx, who.signedInDiscordId(), null);
        ctx.status(204);
    }

    public void verify(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final Authenticators apps = authenticators();
        final CodeAndHold typed = codeOf(ctx);
        accept(apps.check(who.signedInDiscordId(), typed.code(), false));
        sessions.markVerified(who.id(), typed.hold().length());
        Journaling.line(
                ctx,
                who.signedInDiscordId(),
                new JournalDetail.AuthenticatorUsed(typed.hold().length()));
        ctx.status(204);
    }

    /** Refuses to remove the last factor. */
    public void remove(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        final int keys = secondFactor.keysOf(who);
        if (keys == 0) {
            throw new RequestRefused(409, ANSWER.lastFactor());
        }
        if (!authenticators.remove(who.signedInDiscordId())) {
            throw new RequestRefused(404, ANSWER.noAuthenticator());
        }
        Journaling.line(ctx, who.signedInDiscordId(), new JournalDetail.AuthenticatorRemoved(keys));
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
