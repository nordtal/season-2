package eu.nordtal.s2.database.inbox;

import java.util.List;

/** Every inbox table in the network, which V1 declares one by one. */
public final class Inboxes {

    /** One table per consumer. */
    public static final List<InboxTable<?>> ALL =
            List.of(BotRequest.TABLE, ServerRequest.SMP, ServerRequest.HUNGER_GAMES, ServerRequest.LIMBO);

    private Inboxes() {}
}
