package eu.nordtal.s2.commands.remote;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Target;
import org.junit.jupiter.api.Test;

/** A command that can travel has an inbox to travel to, or an adapter would throw the moment somebody types it. */
class InboxCommandRequestsTest {

    @Test
    void everyTargetACommandTravelsToHasAnInbox() {
        for (final Declaration declaration : Catalogue.all()) {
            // The Paper adapters never send to the proxy, and Steward sends only what is released to it.
            if (declaration.target() == Target.PROXY) {
                continue;
            }
            assertTrue(
                    InboxCommandRequests.table(declaration.target()).isPresent(),
                    declaration.name() + " is run by " + declaration.target() + ", which has no inbox");
        }
    }
}
