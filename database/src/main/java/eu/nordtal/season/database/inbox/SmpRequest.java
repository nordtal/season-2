package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;

/** What the SMP can be asked to do by another process; each record is one kind. */
public sealed interface SmpRequest
        permits SmpRequest.CompleteObjective, SmpRequest.UnlockMilestone, SmpRequest.PreviewMessage {

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

    /** Shows an admin's player a text they are trying; refused while that player is not on the server. */
    record PreviewMessage(PlayerId player, MessagePreview preview) implements SmpRequest {

        public PreviewMessage {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
