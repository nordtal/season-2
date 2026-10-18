package eu.nordtal.season.steward.auth;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks the {@link Gate} of every route except the frontend bundle. */
public final class Gatekeeper {

    private static final Logger log = LoggerFactory.getLogger(Gatekeeper.class);

    private final Function<Context, Optional<Sessions.Session>> session;
    private final SecondFactor secondFactor;

    public Gatekeeper(final Function<Context, Optional<Sessions.Session>> session, final SecondFactor secondFactor) {
        this.session = session;
        this.secondFactor = secondFactor;
    }

    /** The checks run in order: session, any key, key held, key recent. */
    public void guard(final Context ctx) {
        final Gate gate = gateOf(ctx);
        if (gate == Gate.ANYONE) {
            return;
        }
        final Sessions.Session who = session.apply(ctx).orElseThrow(() -> new UnauthorizedResponse("sign in first"));
        if (isWrite(ctx)) {
            requireCsrfToken(ctx);
        }
        if (gate == Gate.SIGNED_IN) {
            return;
        }
        secondFactor.requireAKey(who);
        secondFactor.requireKeyHeld(who);
        if (gate == Gate.KEY_FRESH) {
            secondFactor.requireKeyRecently(who);
        }
    }

    /** Returns the {@link Gate} of the route, or {@code ANYONE} for a frontend path without one. */
    private static Gate gateOf(final Context ctx) {
        final List<Gate> decided = ctx.routeRoles().stream()
                .filter(Gate.class::isInstance)
                .map(Gate.class::cast)
                .toList();
        if (decided.size() == 1) {
            return decided.getFirst();
        }
        if (decided.isEmpty() && !isOurs(ctx.path())) {
            return Gate.ANYONE;
        }
        log.error(
                "{} {} carries {} of Steward's own route decisions and has to carry exactly"
                        + " one - refusing it rather than guessing.",
                ctx.method(),
                ctx.path(),
                decided.size());
        throw new UndecidedRoute();
    }

    static boolean isOurs(final String path) {
        return path.startsWith("/api/") || path.startsWith("/auth/") || path.equals("/api") || path.equals("/auth");
    }

    /** Files under {@code /assets/} are immutable, all others {@code no-cache}. */
    public static void cacheHeaders(final Context ctx) {
        if (isOurs(ctx.path())) {
            return;
        }
        ctx.header("Cache-Control", ctx.path().startsWith("/assets/") ? "max-age=31536000, immutable" : "no-cache");
    }

    /** Refuses a write whose CSRF header differs from the CSRF token of the session. */
    private void requireCsrfToken(final Context ctx) {
        final String sent = ctx.header("X-Steward-CSRF");
        final String expected = session.apply(ctx).map(Sessions.Session::csrf).orElse(null);
        if (expected == null || !expected.equals(sent)) {
            throw new ForbiddenResponse("missing or wrong CSRF token");
        }
    }

    private static boolean isWrite(final Context ctx) {
        final String method = ctx.method().name();
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE");
    }

    private static final class UndecidedRoute extends InternalServerErrorResponse {

        private UndecidedRoute() {
            super("This route was built without a decision about whether it needs a security key,"
                    + " so Steward is refusing it rather than guessing. That is a fault in this"
                    + " service and not in what you did.");
        }
    }
}
