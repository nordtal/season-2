package eu.nordtal.season.steward.journal;

import io.javalin.security.RouteRole;

/** Why a changing route writes no journal line of its own; {@code JournaledRoutesTest} names every such route. */
public enum Unjournaled implements RouteRole {

    /** The database writes the line in the transaction of the change, with the admin as actor. */
    IN_THE_CHANGE,

    /** Signing out or ending one's own step-up, as signing in is not journaled either. */
    OWN_SESSION,

    /** The first step of a ceremony, whose second step writes the line. */
    CEREMONY_START,

    /** A text shown to the admin alone. */
    SHOWN_TO_ONESELF
}
