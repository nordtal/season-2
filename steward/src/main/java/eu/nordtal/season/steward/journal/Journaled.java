package eu.nordtal.season.steward.journal;

import eu.nordtal.season.database.audit.JournalAction;
import io.javalin.security.RouteRole;
import java.util.Objects;

/**
 * The journal line a route writes once it has answered with success, declared where the route is registered.
 *
 * The handler names the entry's values through {@link Journaling#line}; {@link Journaling#write} writes it.
 */
public record Journaled(JournalAction action) implements RouteRole {

    public Journaled {
        Objects.requireNonNull(action, "action");
    }

    /** Returns the decision that a route is journaled under {@code action}. */
    public static Journaled as(final JournalAction action) {
        return new Journaled(action);
    }
}
