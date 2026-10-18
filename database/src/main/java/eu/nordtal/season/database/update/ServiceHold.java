package eu.nordtal.season.database.update;

import eu.nordtal.season.common.id.Actor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A service that was stopped on purpose and must stay stopped.
 *
 * @param since when the hold was written, on the database's clock
 * @param heldBy who asked for the DOWN run
 * @param requestId the DOWN run, null if its row was deleted
 */
public record ServiceHold(
        String service,
        Instant since,
        Actor heldBy,
        @Nullable Long requestId) {}
