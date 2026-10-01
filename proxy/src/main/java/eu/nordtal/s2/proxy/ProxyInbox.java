package eu.nordtal.s2.proxy;

import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.ProxyRequest;
import eu.nordtal.s2.database.notify.SignalHub;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * Opens {@code proxy_inbox} on the main proxy: fails what its last start left running, then answers on the hub.
 * The standby runs only during an update, beside the main proxy, so a request asked meanwhile waits for that one.
 */
public final class ProxyInbox {

    private ProxyInbox() {}

    /**
     * Opens the inbox, unless this is the standby.
     *
     * @return whether this proxy answers the inbox
     */
    public static boolean open(
            final ProxyRole role,
            final DataSource pool,
            final SignalHub signals,
            final Inbox.Handler<ProxyRequest> handler,
            final Logger logger) {
        if (role.isStandby()) {
            return false;
        }
        final Inbox<ProxyRequest> inbox = Inbox.over(pool, ProxyRequest.TABLE);
        final int orphans = inbox.settleOrphans(Map.of("error", "the proxy restarted while it ran this"));
        if (orphans > 0) {
            logger.warn("{} request(s) in {} were left running by the last start", orphans, ProxyRequest.TABLE);
        }
        inbox.listen(signals, handler);
        return true;
    }
}
