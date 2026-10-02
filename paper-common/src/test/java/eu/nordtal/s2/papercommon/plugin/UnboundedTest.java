package eu.nordtal.s2.papercommon.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** A server the proxy sends somebody to lets them in, however many its own max-players says fit. */
class UnboundedTest {

    @Test
    void aFullServerStillLetsTheProxysPlayerIn() {
        // true: what Paper passes when its own max-players is reached.
        final PlayerServerFullCheckEvent full = new PlayerServerFullCheckEvent(null, Component.text("full"), true);

        new Unbounded().onFullCheck(full);

        assertTrue(full.isAllowed());
    }
}
