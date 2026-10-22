package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.messages.spec.Display;
import java.util.List;
import java.util.Objects;

/** A request to the Hunger Games server from another process. Each record is one kind. */
public sealed interface HungerGamesRequest
        permits HungerGamesRequest.StartGame,
                HungerGamesRequest.PreviewMessage,
                HungerGamesRequest.PreviewDocument,
                HungerGamesRequest.CheckSettings,
                HungerGamesRequest.StoreModeState,
                HungerGamesRequest.RemoveStack {

    InboxTable<HungerGamesRequest> TABLE = InboxTable.of("hunger_games_inbox", Channel.SERVER, HungerGamesRequest.class)
            .withRows(StartGame.class, StoreModeState.class, RemoveStack.class, PreviewMessage.class);

    /**
     * Starts the registered game.
     *
     * @param confirmed whether the asker has seen the numbers, which a start below the recommended minimum needs
     */
    record StartGame(boolean confirmed) implements HungerGamesRequest {}

    /** Shows a text that an admin tries to the admin's player. Refused while the player is not on the server. */
    record PreviewMessage(
            PlayerId player,
            String key,
            String language,
            String text,
            Display shown,
            String service,
            List<MessagePreview.Example> examples)
            implements HungerGamesRequest {

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
    record PreviewDocument(PlayerId player, DocumentPreview preview) implements HungerGamesRequest {

        public PreviewDocument {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(preview, "preview");
        }
    }

    /** Tries a change to the settings before Steward saves it. Refused when the server would not start with it. */
    record CheckSettings(SettingsChange change) implements HungerGamesRequest {

        public CheckSettings {
            Objects.requireNonNull(change, "change");
        }
    }

    /** Stores a copy of the state the player holds, so Steward shows it as it is. */
    record StoreModeState(PlayerId player) implements HungerGamesRequest {

        public StoreModeState {
            Objects.requireNonNull(player, "player");
        }
    }

    /**
     * Removes the stack Steward showed in a slot of the state the player holds.
     * Refused while the player is not on the server, holds the other mode, or the slot holds another stack.
     */
    record RemoveStack(PlayerId player, Mode mode, Container container, int slot, String item, int amount)
            implements HungerGamesRequest {

        public RemoveStack {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(item, "item");
        }
    }
}
