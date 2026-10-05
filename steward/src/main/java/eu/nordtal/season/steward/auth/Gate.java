package eu.nordtal.season.steward.auth;

import io.javalin.security.RouteRole;

/**
 * What a request has to have proved before a route runs, as one value on every route.
 *
 * The four are a ladder, and {@code GateTest} refuses a build with an endpoint that carries none.
 */
public enum Gate implements RouteRole {

    /** No session at all: the health check, {@code /api/me}, the Discord redirect and the static bundle. */
    ANYONE,

    /**
     * Discord has said who this is, and nothing more.
     *
     * Only the two WebAuthn ceremonies and signing out, since a door cannot ask for the key it hands out.
     */
    SIGNED_IN,

    /** The security key has been held at some point in this session: every reading route in {@code /api}. */
    KEY_HELD,

    /** The security key has been held within the last five minutes: every other writing route. */
    KEY_FRESH
}
