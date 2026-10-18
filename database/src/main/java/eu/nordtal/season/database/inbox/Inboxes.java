package eu.nordtal.season.database.inbox;

import java.util.List;

/** Every inbox table of the network, as V1 declares them one by one. */
public final class Inboxes {

    public static final List<InboxTable<?>> ALL = List.of(
            StewardRequest.TABLE, BotRequest.TABLE, SmpRequest.TABLE, HungerGamesRequest.TABLE, BankRequest.TABLE);

    private Inboxes() {}
}
