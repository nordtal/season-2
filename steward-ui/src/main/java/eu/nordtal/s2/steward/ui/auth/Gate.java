package eu.nordtal.s2.steward.ui.auth;

import io.javalin.security.RouteRole;

/**
 * What a request has to have proved before a route runs - one value, on every route.
 *
 * <h2>Why this is Javalin's own {@code RouteRole} and not a list in a class</h2>
 * A list of dangerous paths somewhere else is a list that goes out of date the first time somebody
 * adds a route, and nothing says so: the new route simply works, for everybody, forever. Carried as
 * a role it sits in the same line as the path and the handler, it cannot be written without being
 * seen, and {@code GateTest} can enumerate every registered endpoint and refuse a build in which
 * one has no value. A route with no decision is the defect this type exists to make impossible.
 *
 * <h2>The four are a ladder, and each rung is a sentence</h2>
 * Each one is everything the one above it is, plus one thing. They are ordered that way on purpose:
 * {@code ordinal()} is not read anywhere, but somebody reading the file should be able to see that
 * the list only ever gets stricter.
 *
 * @see Sessions.Session#verified() the flag {@link #KEY_HELD} reads
 */
public enum Gate implements RouteRole {

    /**
     * No session at all.
     *
     * <p>Four routes and the static bundle: the health check (a container healthcheck carries no
     * cookie), {@code /api/me} (the one route whose answer IS "you are not signed in"), and the two
     * halves of the Discord redirect. Everything else is below this line.</p>
     */
    ANYONE,

    /**
     * Discord has said who this is, and nothing more has been proved.
     *
     * <p>This is the exception list the plan asks for, and it is short because it has to be: the
     * two WebAuthn ceremonies and signing out. <b>A door cannot ask for the key it is there to
     * hand out.</b> Registering the first key is reachable with a Discord session alone - that is
     * the bootstrap, and its window is the time between deploying and signing in once - and
     * registering a <em>second</em> key additionally requires that this session has already held
     * one, which {@code StewardUi} checks in the handler rather than here, because it is a
     * condition on the account and not on the route.</p>
     *
     * <p>Signing out is here for a different reason: a person who cannot complete the key ceremony
     * must still be able to leave. A sign-out behind the key would be a locked room.</p>
     */
    SIGNED_IN,

    /**
     * The security key has been held at some point in this session.
     *
     * <p>Package C. Every reading route in {@code /api} is this: a cookie alone no longer shows
     * anybody the roster, the journal or a server's log. It is the whole of what "Discord alone is
     * not enough" means.</p>
     */
    KEY_HELD,

    /**
     * The security key has been held within the last five minutes.
     *
     * <p>Package D, and <b>every writing route in this service wears it</b> except the three named
     * above. Till widened it on 2026-09-14 from the plan's list - saving a config file and
     * switching the season phase were "not dangerous" in §1 and are now behind the key like
     * everything else, because the line "which of these writes is dangerous" turned out to be
     * harder to defend than "all of them are".</p>
     *
     * <p>Five minutes is Till's decision of the same day: one touch of the key covers everything
     * done inside it, so an evening of work is not an evening of dialogs.</p>
     */
    KEY_FRESH
}
