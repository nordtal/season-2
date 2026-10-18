package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;

/** A request to the Hunger Games server from another process. Each record is one kind. */
public sealed interface HungerGamesRequest permits HungerGamesRequest.StartGame, HungerGamesRequest.PreviewMessage {

    InboxTable<HungerGamesRequest> TABLE = InboxTable.of("hunger_games_inbox", Channel.SERVER, HungerGamesRequest.class)
            .withRows(StartGame.class);

    /**
     * Starts the registered game.
     *
     * @param confirmed whether the asker has seen the numbers, which a start below the recommended minimum needs
     */
    record StartGame(boolean confirmed) implements HungerGamesRequest {}

    /** Shows a text that an admin tries to the admin's player. Refused while the player is not on the server. */
    record PreviewMessage(PlayerId player, MessagePreview preview) implements HungerGamesRequest {

        public PreviewMessage {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
