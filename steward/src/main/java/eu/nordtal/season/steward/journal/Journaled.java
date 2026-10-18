package eu.nordtal.season.steward.journal;

import eu.nordtal.season.database.audit.JournalAction;
import io.javalin.security.RouteRole;
import java.util.Objects;

/** The journal action of a route. The handler adds the values through {@link Journaling#line}. */
public record Journaled(JournalAction action) implements RouteRole {

    public Journaled {
        Objects.requireNonNull(action, "action");
    }

    public static Journaled as(final JournalAction action) {
        return new Journaled(action);
    }
}
