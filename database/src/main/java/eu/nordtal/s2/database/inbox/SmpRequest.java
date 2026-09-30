package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;
import java.util.Objects;

/** What the SMP can be asked to do by another process; each record is one kind. */
public sealed interface SmpRequest permits Reload, SmpRequest.CompleteObjective, SmpRequest.UnlockMilestone {

    /** The SMP's inbox table. */
    InboxTable<SmpRequest> TABLE = InboxTable.of("smp_inbox", Channel.SERVER, SmpRequest.class);

    /** Closes one objective of the active milestone by hand, paying out what was collected. */
    record CompleteObjective(String key) implements SmpRequest {

        public CompleteObjective {
            Objects.requireNonNull(key, "key");
        }
    }

    /** Unlocks the active milestone by hand. */
    record UnlockMilestone(String key) implements SmpRequest {

        public UnlockMilestone {
            Objects.requireNonNull(key, "key");
        }
    }
}
