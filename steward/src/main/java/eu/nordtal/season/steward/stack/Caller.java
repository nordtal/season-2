package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.id.Actor;
import io.javalin.http.Context;

/** The caller of a stack route, as the web sessions know it. */
public interface Caller {

    Actor actor(Context ctx);

    boolean stillSignedIn(Context ctx);
}
