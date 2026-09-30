package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;

/** What the proxy can be asked to do by another process. */
public sealed interface ProxyRequest permits Reload {

    /** The proxy's inbox table. */
    InboxTable<ProxyRequest> TABLE = InboxTable.of("proxy_inbox", Channel.SERVER, ProxyRequest.class);
}
