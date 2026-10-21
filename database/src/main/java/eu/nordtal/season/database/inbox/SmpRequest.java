package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;

/** A request to the SMP from another process. Each record is one kind. */
public sealed interface SmpRequest
        permits SmpRequest.CompleteObjective,
                SmpRequest.UnlockMilestone,
                SmpRequest.SetAura,
                SmpRequest.SetSpins,
                SmpRequest.PreviewMessage,
                SmpRequest.CheckSettings {

    InboxTable<SmpRequest> TABLE = InboxTable.of("smp_inbox", Channel.SERVER, SmpRequest.class)
            .withRows(CompleteObjective.class, UnlockMilestone.class, SetAura.class, SetSpins.class);

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

    /** Sets a person's aura and records the difference as the asker's change. */
    record SetAura(DiscordId person, int aura) implements SmpRequest {

        public SetAura {
            Objects.requireNonNull(person, "person");
        }
    }

    /** Sets the extra spins a person has left, beside the free spin of each day. */
    record SetSpins(DiscordId person, int spins) implements SmpRequest {

        public SetSpins {
            Objects.requireNonNull(person, "person");
            if (spins < 0) {
                throw new IllegalArgumentException("spins are never below zero, not " + spins);
            }
        }
    }

    /** Shows a text that an admin tries to the admin's player. Refused while the player is not on the server. */
    record PreviewMessage(PlayerId player, MessagePreview preview) implements SmpRequest {

        public PreviewMessage {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(preview, "preview");
        }
    }

    /** Tries a change to the settings before Steward saves it. Refused when the server would not start with it. */
    record CheckSettings(SettingsChange change) implements SmpRequest {

        public CheckSettings {
            Objects.requireNonNull(change, "change");
        }
    }
}
