package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;

/** What the Hunger Games server can be asked to do by another process; each record is one kind. */
public sealed interface HungerGamesRequest permits HungerGamesRequest.StartGame {

    /** The Hunger Games server's inbox table. */
    InboxTable<HungerGamesRequest> TABLE =
            InboxTable.of("hunger_games_inbox", Channel.SERVER, HungerGamesRequest.class);

    /**
     * Starts the registered game.
     *
     * @param confirmed whether the asker has seen the numbers, which a start below the recommended minimum needs
     */
    record StartGame(boolean confirmed) implements HungerGamesRequest {}
}
