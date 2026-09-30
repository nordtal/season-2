package eu.nordtal.s2.database.update;

import eu.nordtal.s2.database.Actor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One service that was deliberately stopped and must stay stopped, unlike one that fell over.
 *
 * @param service   the compose service name, as {@code update_request.scope} carries it
 * @param since     when the hold was written, on the database's clock
 * @param heldBy    who asked for the DOWN run that put it there
 * @param requestId the DOWN run that put it there, or {@code null} once that row was deleted
 */
public record ServiceHold(
        String service,
        Instant since,
        Actor heldBy,
        @Nullable Long requestId) {}
