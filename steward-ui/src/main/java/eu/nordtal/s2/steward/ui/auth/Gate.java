package eu.nordtal.s2.steward.ui.auth;

import io.javalin.security.RouteRole;

/**
 * What a request has to have proved before a route runs - one value, on every route.
 *
 * Carried as Javalin's own {@code RouteRole} rather than a list kept elsewhere, so a route cannot
 * be written without a value and {@code GateTest} can enumerate every endpoint and refuse a build
 * missing one. The four are a ladder: each is everything the one above it is, plus one thing.
 *
 * @see Sessions.Session#verified() the flag {@link #KEY_HELD} reads
 */
public enum Gate implements RouteRole {

    /**
     * No session at all.
     *
     * The health check, {@code /api/me}, the two halves of the Discord redirect, and the static
     * bundle. Everything else is below this line.
     */
    ANYONE,

    /**
     * Discord has said who this is, and nothing more has been proved.
     *
     * A short exception list: the two WebAuthn ceremonies and signing out. A door cannot ask for
     * the key it is there to hand out, so registering the first key needs only a Discord session;
     * a second key additionally requires one already held, which is checked in the handler rather
     * than here since it is a condition on the account, not the route. Signing out is here so
     * somebody who cannot complete the key ceremony can still leave.
     */
    SIGNED_IN,

    /**
     * The security key has been held at some point in this session.
     *
     * Every reading route in {@code /api} is this: a cookie alone no longer shows anybody the
     * roster, the journal or a server's log.
     */
    KEY_HELD,

    /**
     * The security key has been held within the last five minutes.
     *
     * Every writing route in this service wears it except the three named above; one touch of the
     * key covers everything done in the five minutes after, so an evening of work is not an
     * evening of dialogs.
     */
    KEY_FRESH
}
