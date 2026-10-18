package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;

/** A request to the SMP from another process. Each record is one kind. */
public sealed interface SmpRequest
        permits SmpRequest.CompleteObjective, SmpRequest.UnlockMilestone, SmpRequest.PreviewMessage {

    InboxTable<SmpRequest> TABLE = InboxTable.of("smp_inbox", Channel.SERVER, SmpRequest.class)
            .withRows(CompleteObjective.class, UnlockMilestone.class);

    /** Closes one objective of the active milestone by hand and pays out what was collected. */
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

    /** Shows a text that an admin tries to the admin's player. Refused while the player is not on the server. */
    record PreviewMessage(PlayerId player, MessagePreview preview) implements SmpRequest {

        public PreviewMessage {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
