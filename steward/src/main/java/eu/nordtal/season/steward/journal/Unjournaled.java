package eu.nordtal.season.steward.journal;

import io.javalin.security.RouteRole;

/** Why a changing route writes no journal line of its own; {@code JournaledRoutesTest} names every such route. */
public enum Unjournaled implements RouteRole {

    /**
     * {@code :database} writes the line in the transaction that makes the change, with the admin as actor.
     *
     * So the line exists exactly when the change does: a request row with its line, or the bot carrying one out.
     */
    IN_THE_CHANGE,

    /** Signing out or ending one's own step-up: a session's own course, which signing in does not journal either. */
    OWN_SESSION,

    /** The first half of a ceremony, whose second half writes the line. */
    CEREMONY_START,

    /** A text shown to the admin alone, which changes nothing anybody else sees. */
    SHOWN_TO_ONESELF
}
