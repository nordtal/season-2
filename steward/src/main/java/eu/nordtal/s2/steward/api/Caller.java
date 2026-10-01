package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.database.Actor;
import io.javalin.http.Context;

/** Who is asking, as the web's sessions know it; the stack routes hold no session of their own. */
public interface Caller {

    /** The signed-in admin as {@code name (id)}, for a row that records who asked. */
    String name(Context ctx);

    /** The signed-in admin as the actor a stored change records. */
    Actor actor(Context ctx);

    /** Whether the session behind {@code ctx} still exists; a long follow asks this at most once a second. */
    boolean stillSignedIn(Context ctx);
}
