package eu.nordtal.season.steward.api;

import eu.nordtal.season.common.id.Actor;
import io.javalin.http.Context;

/** Who is asking, as the web's sessions know it; the stack routes hold no session of their own. */
public interface Caller {

    /** The signed-in admin as the actor a stored change records. */
    Actor actor(Context ctx);

    /** Whether the session behind {@code ctx} still exists; a long follow asks this at most once a second. */
    boolean stillSignedIn(Context ctx);
}
