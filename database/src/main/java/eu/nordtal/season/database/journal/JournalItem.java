package eu.nordtal.season.database.journal;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.AuditEntry;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of the journal's stream: an entry, an alert or a run, exactly one of the three set.
 *
 * @param key orders rows of the same instant and names the row in a cursor
 * @param occurred when the entry was written, or when the run finished or was asked for while it has not
 * @param opened when the problem an alert raises or clears began, null for an alert that is no measurement
 */
public record JournalItem(
        String key,
        Instant occurred,
        Actor actor,
        @Nullable AuditEntry entry,
        @Nullable RaisedAlert alert,
        @Nullable Instant opened,
        @Nullable JournalRun run) {}
