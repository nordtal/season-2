package eu.nordtal.season.steward.web;

import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.auth.Sessions;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The one filter every route but the frontend bundle passes through. */
final class Gatekeeper {

    private static final Logger log = LoggerFactory.getLogger(Gatekeeper.class);

    private final Function<Context, Optional<Sessions.Session>> session;
    private final SecondFactor secondFactor;

    Gatekeeper(final Function<Context, Optional<Sessions.Session>> session, final SecondFactor secondFactor) {
        this.session = session;
        this.secondFactor = secondFactor;
    }

    /**
     * The one door, in front of every matched endpoint, reading the {@link Gate} off the route.
     *
     * Checks run in the order a person experiences them: who, then a key at all, then held here, then held recently.
     */
    void guard(final Context ctx) {
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

    /** The one decision this route carries; zero or more than one are refused rather than guessed. */
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

    /**
     * Whether a path lies under {@code /api} or {@code /auth}; everything else is the frontend bundle, open to anyone.
     *
     * {@code GateTest} fails the build when something is registered outside these prefixes without a decision.
     */
    static boolean isOurs(final String path) {
        return path.startsWith("/api/") || path.startsWith("/auth/") || path.equals("/api") || path.equals("/auth");
    }

    /**
     * Sets the frontend bundle's cache headers after the request is answered.
     *
     * Hashed {@code /assets} are immutable; {@code index.html} is {@code no-cache}, so it is always revalidated.
     */
    static void cacheHeaders(final Context ctx) {
        if (isOurs(ctx.path())) {
            return;
        }
        ctx.header("Cache-Control", ctx.path().startsWith("/assets/") ? "max-age=31536000, immutable" : "no-cache");
    }

    /** Double submit: a form from another site can carry the cookie but cannot read the token {@code /api/me} gives. */
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

    /** A route registered without a {@link Gate}; a 500, since nothing the caller did is wrong. */
    private static final class UndecidedRoute extends InternalServerErrorResponse {

        private UndecidedRoute() {
            super("This route was built without a decision about whether it needs a security key,"
                    + " so Steward is refusing it rather than guessing. That is a fault in this"
                    + " service and not in what you did.");
        }
    }
}
