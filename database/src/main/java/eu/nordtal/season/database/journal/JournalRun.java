package eu.nordtal.season.database.journal;

import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import java.util.List;

/**
 * A run as the journal shows it.
 *
 * @param services the services it acts on
 * @param why a line for each step that failed or warned, in order
 */
public record JournalRun(long id, UpdateKind kind, UpdateStatus status, List<String> services, List<MessageRef> why) {

    public JournalRun {
        services = List.copyOf(services);
        why = List.copyOf(why);
    }
}
