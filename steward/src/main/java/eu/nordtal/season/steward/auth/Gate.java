package eu.nordtal.season.steward.auth;

import io.javalin.security.RouteRole;

/** The proof a request must show before a route runs. */
public enum Gate implements RouteRole {

    /** Requires no session. */
    ANYONE,

    /** Requires a Discord sign-in only. */
    SIGNED_IN,

    /** Requires that the session has used a factor. */
    KEY_HELD,

    /** Requires a step-up that still holds. */
    KEY_FRESH
}
