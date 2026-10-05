package eu.nordtal.season.database.inbox;

import java.util.List;

/** Every inbox table in the network, which V1 declares one by one. */
public final class Inboxes {

    /** One table per consumer. */
    public static final List<InboxTable<?>> ALL = List.of(
            StewardRequest.TABLE, BotRequest.TABLE, SmpRequest.TABLE, HungerGamesRequest.TABLE, BankRequest.TABLE);

    private Inboxes() {}
}
