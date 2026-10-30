package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.spec.Display;
import java.util.List;
import java.util.Objects;

/** A request to the SMP from another process. Each record is one kind. */
public sealed interface SmpRequest
        permits SmpRequest.CompleteObjective,
                SmpRequest.UnlockMilestone,
                SmpRequest.SetAura,
                SmpRequest.SetSpins,
                SmpRequest.PreviewMessage,
                SmpRequest.PreviewDocument,
                SmpRequest.CheckSettings,
                SmpRequest.StoreModeState,
                SmpRequest.RemoveStack,
                SmpRequest.OpenInventory {

    InboxTable<SmpRequest> TABLE = InboxTable.of("smp_inbox", Channel.SERVER, SmpRequest.class)
            .withRows(
                    CompleteObjective.class,
                    UnlockMilestone.class,
                    SetAura.class,
                    SetSpins.class,
                    StoreModeState.class,
                    RemoveStack.class,
                    OpenInventory.class,
                    PreviewMessage.class,
                    PreviewDocument.class);

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
    record PreviewMessage(
            PlayerId player,
            String key,
            String language,
            String text,
            Display shown,
            String service,
            List<MessagePreview.Example> examples)
            implements SmpRequest {

        public PreviewMessage {
            Objects.requireNonNull(player, "player");
            examples = new MessagePreview(key, language, text, shown, service, examples).examples();
        }

        public PreviewMessage(final PlayerId player, final MessagePreview preview) {
            this(
                    player,
                    preview.key(),
                    preview.language(),
                    preview.text(),
                    preview.shown(),
                    preview.service(),
                    preview.examples());
        }

        /** Returns the tried text. */
        public MessagePreview preview() {
            return new MessagePreview(key, language, text, shown, service, examples);
        }
    }

    /** Shows a drafted document to the admin's player. Refused while the player is not on the server. */
    record PreviewDocument(
            PlayerId player, String namespace, String name, String language, Display shown, Document document)
            implements SmpRequest {

        public PreviewDocument {
            Objects.requireNonNull(player, "player");
            document = new DocumentPreview(namespace, name, language, shown, document).document();
        }

        public PreviewDocument(final PlayerId player, final DocumentPreview preview) {
            this(player, preview.namespace(), preview.name(), preview.language(), preview.shown(), preview.document());
        }

        /** Returns the drafted document. */
        public DocumentPreview preview() {
            return new DocumentPreview(namespace, name, language, shown, document);
        }
    }

    /** Tries a change to the settings before Steward saves it. Refused when the server would not start with it. */
    record CheckSettings(SettingsChange change) implements SmpRequest {

        public CheckSettings {
            Objects.requireNonNull(change, "change");
        }
    }

    /** Stores a copy of the state the player holds, so Steward shows it as it is. */
    record StoreModeState(PlayerId player) implements SmpRequest {

        public StoreModeState {
            Objects.requireNonNull(player, "player");
        }
    }

    /**
     * Removes the stack Steward showed in a slot of the state the player holds.
     * Refused while the player is not on the server, holds the other mode, or the slot holds another stack.
     */
    record RemoveStack(PlayerId player, Mode mode, Container container, int slot, String item, int amount)
            implements SmpRequest {

        public RemoveStack {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * Opens an editor of the player's Survival {@code container} on this server for the admin, who is on it.
     * Refused while the admin is not on the server or the state is not here to edit.
     */
    record OpenInventory(PlayerId admin, PlayerId player, Container container) implements SmpRequest {

        public OpenInventory {
            Objects.requireNonNull(admin, "admin");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(container, "container");
        }
    }
}
