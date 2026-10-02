package eu.nordtal.s2.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.ProxyRequest;
import eu.nordtal.s2.database.inbox.Reload;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standby proxy runs only during an update, beside the main one, so it leaves {@code proxy_inbox} alone.
 *
 * Skips without Docker. A request the main proxy is carrying out stays running, and a new one waits for it.
 */
class ProxyInboxIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyInboxIntegrationTest.class);

    private TestDatabase database;
    private Inbox<ProxyRequest> inbox;
    private long running;

    @BeforeEach
    void plantARunningReload() {
        database = TestDatabase.fresh();
        inbox = Inbox.over(database.dataSource(), ProxyRequest.TABLE);
        running = inbox.submit(new Reload(), Actor.STEWARD).id();
        assertEquals(running, inbox.claim().orElseThrow().id(), "the planted reload is the main proxy's now");
    }

    @Test
    void theStandbyNeitherFailsARunningRequestNorAnswersTheInbox() {
        final SignalHub signals = hub();

        final boolean answers = ProxyInbox.open(
                ProxyRole.STANDBY, database.dataSource(), signals, request -> Outcome.done("answered by the standby"));

        assertEquals(
                InboxStatus.RUNNING,
                inbox.find(running).orElseThrow().status(),
                "the standby's start failed a reload the main proxy was still carrying out");
        assertEquals(Set.of(), signals.channels(), "the standby listens on the inbox's channel");
        assertFalse(answers, "the standby must leave the inbox to the main proxy");
    }

    @Test
    void theMainProxyFailsWhatItsLastStartLeftRunningAndAnswersTheInbox() {
        final SignalHub signals = hub();

        final boolean answers = ProxyInbox.open(
                ProxyRole.LIVE, database.dataSource(), signals, request -> Outcome.done("answered by the main proxy"));

        assertTrue(answers);
        assertEquals(InboxStatus.FAILED, inbox.find(running).orElseThrow().status());
        assertTrue(signals.channels().contains(Channel.SERVER), "the main proxy never hears a new request");
    }

    private SignalHub hub() {
        return SignalHub.open(database.jdbcUrl(), database.username(), database.password(), 5, "proxy-test", LOG);
    }
}
