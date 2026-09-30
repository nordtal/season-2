package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;

/** What the waiting room can be asked to do by another process. */
public sealed interface LimboRequest permits Reload {

    /** Limbo's inbox table. */
    InboxTable<LimboRequest> TABLE = InboxTable.of("limbo_inbox", Channel.SERVER, LimboRequest.class);
}
