package eu.nordtal.season.database.season;

import eu.nordtal.season.common.id.Actor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One stretch of maintenance, as the journal holds it.
 *
 * @param on when it was turned on
 * @param off when it was turned off, null while it lasts
 * @param by who turned it on
 */
public record MaintenanceSpan(Instant on, @Nullable Instant off, Actor by) {}
