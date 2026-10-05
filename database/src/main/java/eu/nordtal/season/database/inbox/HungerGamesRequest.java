package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;

/** What the Hunger Games server can be asked to do by another process; each record is one kind. */
public sealed interface HungerGamesRequest permits HungerGamesRequest.StartGame, HungerGamesRequest.PreviewMessage {

    /** The Hunger Games server's inbox table. */
    InboxTable<HungerGamesRequest> TABLE =
            InboxTable.of("hunger_games_inbox", Channel.SERVER, HungerGamesRequest.class);

    /**
     * Starts the registered game.
     *
     * @param confirmed whether the asker has seen the numbers, which a start below the recommended minimum needs
     */
    record StartGame(boolean confirmed) implements HungerGamesRequest {}

    /** Shows an admin's player a text they are trying; refused while that player is not on the server. */
    record PreviewMessage(PlayerId player, MessagePreview preview) implements HungerGamesRequest {

        public PreviewMessage {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
